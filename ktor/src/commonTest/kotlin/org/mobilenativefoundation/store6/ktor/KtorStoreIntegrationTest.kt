@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class KtorStoreIntegrationTest {
    @Test
    fun invalidateThenConditionalRefetch_emitsOneRevalidatedAndClearsStaleness() = runTest {
        var requests = 0
        val ifNoneMatchHeaders = mutableListOf<String?>()
        val engine =
            MockEngine { request ->
                ifNoneMatchHeaders += request.headers[HttpHeaders.IfNoneMatch]
                when (++requests) {
                    1 ->
                        respond(
                            content = "v1",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ETag, "\"v1\""),
                        )

                    else -> respond(content = "", status = HttpStatusCode.NotModified)
                }
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            val key = IntegrationKey("revalidation")
            try {
                assertEquals("v1", store.get(key))
                store.invalidate(key)

                store.stream(key).test {
                    var revalidatedCount = 0
                    while (revalidatedCount == 0) {
                        when (val result = awaitItem()) {
                            is StoreResult.Data -> {
                                assertEquals("v1", result.value)
                                assertTrue(result.isStale)
                            }

                            is StoreResult.Revalidated -> revalidatedCount += 1
                            is StoreResult.Error ->
                                fail("unexpected Store error: ${result.error}")

                            is StoreResult.Loading ->
                                fail("resident invalidation must not emit Loading")
                        }
                    }

                    assertEquals(1, revalidatedCount)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }

                assertTrue(
                    requests in 2..3,
                    "the 304 cycle may self-heal one obsolete cold-baseline launch",
                )
                assertNull(ifNoneMatchHeaders[0])
                ifNoneMatchHeaders.drop(1).forEach { header ->
                    assertEquals("\"v1\"", header)
                }
                val requestsAfterRevalidated = requests
                assertEquals("v1", store.get(key))
                assertEquals(requestsAfterRevalidated, requests)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun trulyColdNotModified_surfacesMissing() = runTest {
        val engine =
            MockEngine { request ->
                assertNull(request.headers[HttpHeaders.IfNoneMatch])
                assertNull(request.headers[HttpHeaders.IfModifiedSince])
                respond(content = "", status = HttpStatusCode.NotModified)
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        errorMapper =
                            KtorErrorMapper { exchange ->
                                if (exchange.status == HttpStatusCode.NotModified) {
                                    KtorOutcome.NotModified(null)
                                } else {
                                    KtorOutcome.Defer
                                }
                            },
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            try {
                val failure =
                    assertFailsWith<StoreException> {
                        store.get(IntegrationKey("cold-304"))
                    }
                assertIs<StoreError.Missing>(failure.error)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun mustBeFresh_reRequestsInsteadOfServingResident() = runTest {
        var requests = 0
        val engine =
            MockEngine {
                requests += 1
                respond(content = "v$requests", status = HttpStatusCode.OK)
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            val key = IntegrationKey("freshness")
            try {
                assertEquals("v1", store.get(key))
                assertEquals("v2", store.get(key, Freshness.MustBeFresh))
                assertEquals(2, requests)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun serverError_surfacesTypedKtorFetchException() = runTest {
        val engine =
            MockEngine {
                respond(content = "failure", status = HttpStatusCode.InternalServerError)
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            try {
                val failure =
                    assertFailsWith<StoreException> {
                        store.get(IntegrationKey("typed-error"))
                    }
                val fetchError =
                    failure.error as? StoreError.Fetch
                        ?: fail("expected StoreError.Fetch, was ${failure.error}")
                val cause =
                    fetchError.cause as? KtorFetchException
                        ?: fail("expected KtorFetchException, was ${fetchError.cause}")

                assertEquals(HttpStatusCode.InternalServerError, cause.status)
                assertEquals(HttpMethod.Get, cause.method)
                assertEquals("https://example.test/items/typed-error", cause.url)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun postEvictionHydration_fetchesWithoutResidentValidator() = runTest {
        val conditionalHeaders = mutableListOf<Pair<String?, String?>>()
        val engine =
            MockEngine { request ->
                conditionalHeaders +=
                    request.headers[HttpHeaders.IfNoneMatch] to
                    request.headers[HttpHeaders.IfModifiedSince]
                val requestNumber = conditionalHeaders.size
                respond(
                    content = "v$requestNumber",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ETag, "\"v$requestNumber\""),
                )
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    maxIdleKeys(0)
                    persistence(FakeSourceOfTruth())
                    ktorFetcher(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            val key = IntegrationKey("validator-lifetime")
            try {
                assertEquals("v1", store.get(key))
                assertEquals("v2", store.get(key, Freshness.MustBeFresh))
                val expectedHeaders =
                    listOf<Pair<String?, String?>>(null to null, null to null)
                assertEquals(expectedHeaders, conditionalHeaders)
            } finally {
                store.close()
            }
        }
    }
}

private class IntegrationKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("ktor-integration")

    override fun canonicalId(): String = id
}

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
