package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration

abstract class StoreRevalidationConformance : SourceOfTruthSubstitutionTest() {
    @Test
    fun conditionalRefetch_notModified_emitsOwnerRevalidatedAndClearsStaleness() = runTest {
        var calls = 0
        val notModifiedGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> {
                        notModifiedGate.await()
                        FetcherResult.NotModified("e1")
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            store.stream(TestKey("1")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                store.invalidate(TestKey("1"))
                notModifiedGate.complete(Unit)

                while (true) {
                    when (val item = awaitItem()) {
                        is StoreResult.Data -> {
                            if (item.origin == Origin.FETCHER && !item.isStale) {
                                fail("legacy fresh FETCHER Data must be replaced by Revalidated")
                            }
                        }
                        is StoreResult.Revalidated -> {
                            assertTrue(item.age >= Duration.ZERO)
                            break
                        }
                        else -> fail("unexpected lifecycle item ${item::class.simpleName}")
                    }
                }
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals("v1", store.get(TestKey("1")))
            assertEquals(2, calls, "successful 304 must clear staleness before later planning")
        } finally {
            notModifiedGate.complete(Unit)
            store.close()
        }
    }
}

class StoreRevalidationConformanceTest : StoreRevalidationConformance()
