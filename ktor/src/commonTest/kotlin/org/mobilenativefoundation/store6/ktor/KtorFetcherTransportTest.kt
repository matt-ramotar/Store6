@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KtorFetcherTransportTest {
    @Test
    fun ok_withBodyAndEtag_successStoresEtagVerbatim() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ETag, "\"v1\""),
                    )
                }

            HttpClient(engine).use { client ->
                val result = assertIs<FetcherResult.Success<String>>(transportFetcher(client).fetch(KEY, null))
                assertEquals("payload", result.value)
                assertEquals("\"v1\"", result.etag)
            }
        }

    @Test
    fun ok_onlyLastModified_successUsesLmToken() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.LastModified, LM_DATE),
                    )
                }

            HttpClient(engine).use { client ->
                val result = assertIs<FetcherResult.Success<String>>(transportFetcher(client).fetch(KEY, null))
                assertEquals("payload", result.value)
                assertEquals("LM:$LM_DATE", result.etag)
            }
        }

    @Test
    fun ok_onlyLastModified_fallbackDisabled_successWithNullToken() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.LastModified, LM_DATE),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.Success<String>>(
                        transportFetcher(client, lastModifiedFallback = false).fetch(KEY, null),
                    )
                assertEquals("payload", result.value)
                assertNull(result.etag)
            }
        }

    @Test
    fun ok_bothValidators_etagWins() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ETag to listOf("\"v1\""),
                                HttpHeaders.LastModified to listOf(LM_DATE),
                            ),
                    )
                }

            HttpClient(engine).use { client ->
                val result = assertIs<FetcherResult.Success<String>>(transportFetcher(client).fetch(KEY, null))
                assertEquals("\"v1\"", result.etag)
            }
        }

    @Test
    fun partialContent_isErrorWithStatus() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "range", status = HttpStatusCode.PartialContent)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.PartialContent,
                )
            }
        }

    @Test
    fun noContent_decodeThrows_errorPreservesOriginalExceptionType() =
        runTest {
            val decodeFailure = EmptyBodyException()
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NoContent)
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.Error>(
                        transportFetcher(
                            client,
                            decode = { throw decodeFailure },
                        ).fetch(KEY, null),
                    )
                assertSame(decodeFailure, result.cause)
                assertFalse(result.cause is KtorFetchException)
            }
        }

    @Test
    fun resetContent_decodeThrows_errorPreservesOriginalExceptionType() =
        runTest {
            val decodeFailure = EmptyBodyException()
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.ResetContent)
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.Error>(
                        transportFetcher(
                            client,
                            decode = { throw decodeFailure },
                        ).fetch(KEY, null),
                    )
                assertSame(decodeFailure, result.cause)
                assertFalse(result.cause is KtorFetchException)
            }
        }

    @Test
    fun conditionalEtag_sendsIfNoneMatchOnly_304adoptsResponseEtag() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(listOf("\"v1\""), request.headers.getAll(HttpHeaders.IfNoneMatch))
                    assertNoHeader(request, HttpHeaders.IfModifiedSince)
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.ETag, "\"v2\""),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.NotModified>(
                        transportFetcher(client).fetch(KEY, "\"v1\""),
                    )
                assertEquals("\"v2\"", result.etag)
            }
        }

    @Test
    fun conditionalLastModified_sendsIfModifiedSinceOnly_304withoutEtagIsNoFlip() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(listOf(LM_DATE), request.headers.getAll(HttpHeaders.IfModifiedSince))
                    assertNoHeader(request, HttpHeaders.IfNoneMatch)
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.LastModified, LM_DATE),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.NotModified>(
                        transportFetcher(client).fetch(KEY, "LM:$LM_DATE"),
                    )
                assertNull(result.etag)
            }
        }

    @Test
    fun unconditional304_isErrorWithNotModifiedStatus() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertNoConditionalHeaders(request)
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.NotModified,
                )
            }
        }

    @Test
    fun post_doesNotSendConditionalHeaders_304isAnomalyError() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertNoConditionalHeaders(request)
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(
                        client,
                        configureRequest = { key ->
                            method = HttpMethod.Post
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    ).fetch(KEY, "\"v1\""),
                    HttpStatusCode.NotModified,
                )
            }
        }

    @Test
    fun replaceNotAppend_etagPlan_keepsExactlyOneIfNoneMatch() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(listOf("\"v1\""), request.headers.getAll(HttpHeaders.IfNoneMatch))
                    assertNoHeader(request, HttpHeaders.IfModifiedSince)
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.ETag, "\"v1\""),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.NotModified>(
                        transportFetcher(client, configureRequest = staleConditionalHeaders()).fetch(KEY, "\"v1\""),
                    )
                assertEquals("\"v1\"", result.etag)
            }
        }

    @Test
    fun replaceNotAppend_nullEtag_stripsCallerConditionalHeaders() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertNoConditionalHeaders(request)
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ETag, "\"fresh\""),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.Success<String>>(
                        transportFetcher(client, configureRequest = staleConditionalHeaders()).fetch(KEY, null),
                    )
                assertEquals("payload", result.value)
                assertEquals("\"fresh\"", result.etag)
            }
        }

    @Test
    fun notFound_defaultPolicy_isError() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NotFound)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.NotFound,
                )
            }
        }

    @Test
    fun notFound_deletePolicy_isDeleted() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NotFound)
                }

            HttpClient(engine).use { client ->
                assertEquals(
                    FetcherResult.Deleted,
                    transportFetcher(client, notFoundPolicy = KtorNotFoundPolicy.Delete).fetch(KEY, null),
                )
            }
        }

    @Test
    fun gone_defaultPolicy_isError() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.Gone)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.Gone,
                )
            }
        }

    @Test
    fun gone_deletePolicy_isDeleted() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.Gone)
                }

            HttpClient(engine).use { client ->
                assertEquals(
                    FetcherResult.Deleted,
                    transportFetcher(client, notFoundPolicy = KtorNotFoundPolicy.Delete).fetch(KEY, null),
                )
            }
        }

    @Test
    fun serverError_preservesStatus() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.InternalServerError)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.InternalServerError,
                )
            }
        }

    @Test
    fun redirectOtherThan304_isError() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "",
                        status = HttpStatusCode.MovedPermanently,
                        headers = headersOf(HttpHeaders.Location, "https://example.test/elsewhere"),
                    )
                }

            HttpClient(engine) {
                followRedirects = false
            }.use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.MovedPermanently,
                )
            }
        }

    @Test
    fun transportFailure_preservesOriginalException() =
        runTest {
            val boom = TransportIoException()
            val engine = MockEngine { throw boom }

            HttpClient(engine).use { client ->
                val result = assertIs<FetcherResult.Error>(transportFetcher(client).fetch(KEY, null))
                assertSame(boom, result.cause)
                assertFalse(result.cause is KtorFetchException)
            }
        }

    @Test
    fun cancellation_propagatesAndDoesNotReturnError() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    started.complete(Unit)
                    delay(60_000)
                    respond(content = "late", status = HttpStatusCode.OK)
                }

            HttpClient(engine).use { client ->
                val fetcher = transportFetcher(client)
                var returned: FetcherResult<String>? = null
                val job =
                    launch {
                        try {
                            returned = fetcher.fetch(KEY, null)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        }
                    }
                started.await()
                job.cancel()
                val joinFailure = runCatching { job.join() }.exceptionOrNull()
                assertNull(returned)
                assertTrue(job.isCancelled)
                if (joinFailure != null) {
                    assertIs<CancellationException>(joinFailure)
                }
            }
        }

    @Test
    fun errorMapper_failOnOk_skipsDecode() =
        runTest {
            var decoded = false
            val custom =
                KtorFetchException(
                    status = HttpStatusCode.BadRequest,
                    method = HttpMethod.Get,
                    url = "https://example.test/mapped",
                    message = "mapper fail",
                )
            val engine =
                MockEngine {
                    respond(content = "payload", status = HttpStatusCode.OK)
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.Error>(
                        transportFetcher(
                            client,
                            errorMapper = KtorErrorMapper { KtorOutcome.Fail(custom) },
                            decode = {
                                decoded = true
                                it.bodyAsText()
                            },
                        ).fetch(KEY, null),
                    )
                assertSame(custom, result.cause)
                assertFalse(decoded)
            }
        }

    @Test
    fun errorMapper_defer_appliesDefaultTable() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.InternalServerError)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(
                        client,
                        errorMapper = KtorErrorMapper { KtorOutcome.Defer },
                    ).fetch(KEY, null),
                    HttpStatusCode.InternalServerError,
                )
            }
        }

    @Test
    fun errorMapper_notModifiedOnUnconditional304_overridesAnomaly() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.NotModified>(
                        transportFetcher(
                            client,
                            errorMapper =
                                KtorErrorMapper { exchange ->
                                    if (exchange.status == HttpStatusCode.NotModified) {
                                        KtorOutcome.NotModified("\"override\"")
                                    } else {
                                        KtorOutcome.Defer
                                    }
                                },
                        ).fetch(KEY, null),
                    )
                assertEquals("\"override\"", result.etag)
            }
        }

    @Test
    fun errorMapper_deleteOnNotFound_isDeleted() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NotFound)
                }

            HttpClient(engine).use { client ->
                assertEquals(
                    FetcherResult.Deleted,
                    transportFetcher(
                        client,
                        errorMapper = KtorErrorMapper { KtorOutcome.Delete },
                    ).fetch(KEY, null),
                )
            }
        }

    private fun transportFetcher(
        client: HttpClient,
        notFoundPolicy: KtorNotFoundPolicy = KtorNotFoundPolicy.Error,
        lastModifiedFallback: Boolean = true,
        errorMapper: KtorErrorMapper = KtorErrorMapper.Default,
        decode: suspend (HttpResponse) -> String = DefaultDecode,
        configureRequest: HttpRequestBuilder.(TransportKey) -> Unit = DefaultConfigure,
    ): Fetcher<TransportKey, String> =
        ktorFetcher(
            client = client,
            notFoundPolicy = notFoundPolicy,
            lastModifiedFallback = lastModifiedFallback,
            errorMapper = errorMapper,
            decode = decode,
            configureRequest = configureRequest,
        )

    private fun staleConditionalHeaders(): HttpRequestBuilder.(TransportKey) -> Unit =
        { key ->
            url("https://example.test/items/${key.canonicalId()}")
            headers.append(HttpHeaders.IfNoneMatch, "stale")
            headers.append(HttpHeaders.IfModifiedSince, LM_DATE)
        }

    private fun assertStatusError(
        result: FetcherResult<String>,
        status: HttpStatusCode,
    ) {
        val error = assertIs<FetcherResult.Error>(result)
        val cause = assertIs<KtorFetchException>(error.cause)
        assertEquals(status, cause.status)
    }

    private fun assertNoConditionalHeaders(request: HttpRequestData) {
        assertNoHeader(request, HttpHeaders.IfNoneMatch)
        assertNoHeader(request, HttpHeaders.IfModifiedSince)
    }

    private fun assertNoHeader(
        request: HttpRequestData,
        name: String,
    ) {
        val values = request.headers.getAll(name)
        assertTrue(values.isNullOrEmpty(), "expected no $name, found $values")
    }

    private companion object {
        val KEY = TransportKey("1")
        const val LM_DATE = "Wed, 21 Oct 2015 07:28:00 GMT"
        val DefaultDecode: suspend (HttpResponse) -> String = { response ->
            val text = response.bodyAsText()
            if (text.isEmpty()) throw EmptyBodyException()
            text
        }
        val DefaultConfigure: HttpRequestBuilder.(TransportKey) -> Unit = { key ->
            url("https://example.test/items/${key.canonicalId()}")
        }
    }
}

private class TransportKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("ktor-transport")

    override fun canonicalId(): String = id
}

private class EmptyBodyException : IllegalStateException("empty body")

private class TransportIoException : Exception("io failure")
