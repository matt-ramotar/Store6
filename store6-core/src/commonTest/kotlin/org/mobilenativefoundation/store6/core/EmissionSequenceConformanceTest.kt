package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmissionSequenceConformanceTest {
    @Test
    fun ac1a_staleWhileRevalidate_successEmitsStaleThenExactlyOneFreshData() = runTest {
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            store.invalidate(TestKey("1"))

            store.stream(TestKey("1")).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                secondStarted.await()
                secondGate.complete(Unit)

                val fresh = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                expectNoEvents()
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun ac1b_staleWhileRevalidate_failureEmitsStaleThenExactlyOneServedStaleError() = runTest {
        var calls = 0
        val boom = IllegalStateException("boom")
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        throw boom
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            store.invalidate(TestKey("1"))

            store.stream(TestKey("1")).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                secondStarted.await()
                secondGate.complete(Unit)

                val failure = assertIs<StoreResult.Error>(awaitItem())
                val fetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(fetch.cause === boom)
                assertTrue(failure.servedStale)
                expectNoEvents()
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun ac1c_invalidationRacingInitialFailureEmitsOneErrorThenSecondCycleData() = runTest {
        var calls = 0
        val boom = IllegalStateException("boom")
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcherOfResult {
                when (++calls) {
                    1 -> {
                        firstStarted.complete(Unit)
                        firstGate.await()
                        FetcherResult.Error(boom)
                    }
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        FetcherResult.Success("v2")
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            store.stream(TestKey("1")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                firstStarted.await()

                store.invalidate(TestKey("1"))
                firstGate.complete(Unit)

                val failure = assertIs<StoreResult.Error>(awaitItem())
                val fetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(fetch.cause === boom)
                assertFalse(failure.servedStale)

                withTimeout(1_000) { secondStarted.await() }
                expectNoEvents()
                secondGate.complete(Unit)

                val fresh = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                expectNoEvents()
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun ac1d_notModifiedEmitsExactlyOneFreshDataWithoutRevalidated() = runTest {
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        FetcherResult.NotModified(etag = "e1")
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            store.invalidate(TestKey("1"))

            store.stream(TestKey("1")).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                secondStarted.await()
                secondGate.complete(Unit)

                val fresh = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                expectNoEvents()
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }
}
