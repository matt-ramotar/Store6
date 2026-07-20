package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

open class EmissionSequenceConformanceTest : SourceOfTruthSubstitutionTest() {
    @Test
    fun ac1a_staleWhileRevalidate_successEmitsStaleThenExactlyOneFreshData() = runTest {
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
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
            val key = TestKey("1")
            turbineScope {
                // A successful one-shot get proves the SoT publication edge, but it does not
                // start the shared reader pipeline. Keep a live seed collector so its v1 Data is
                // also the causal barrier for the queued writer echo before revalidation begins.
                val initialCollector = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(initialCollector.awaitItem())
                val initial = assertIs<StoreResult.Data<String>>(initialCollector.awaitItem())
                assertEquals("v1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)

                store.invalidate(key)
                // Prove the retained seed collector has processed invalidation and registered
                // the gated refetch before the target collector joins it. Otherwise a delayed
                // seed watcher can first run in the slot-settle/write-through I3 window.
                secondStarted.await()
                val collector = store.stream(key).testIn(backgroundScope)
                val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                secondGate.complete(Unit)

                var fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                var queuedStaleReplays = 0
                while (fresh.value == "v1") {
                    // At-least-latest Data permits a queued stale replay to reach a fast
                    // collector; it may not replace or follow the one clean terminal value.
                    queuedStaleReplays += 1
                    assertEquals(1, queuedStaleReplays, "more than one queued stale replay")
                    assertTrue(fresh.isStale)
                    assertTrue(fresh.refreshing)
                    fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                }
                assertEquals("v2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                collector.expectNoEvents()
                assertEquals(2, calls)
                initialCollector.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
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
        val store = testStore<TestKey, String> {
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
        val store = testStore<TestKey, String> {
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

                withContext(Dispatchers.Default) {
                    withTimeout(1_000) { secondStarted.await() }
                }
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
    fun ac1d_notModifiedEmitsExactlyOneRevalidatedWithoutFreshData() = runTest {
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
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
            turbineScope {
                // A changing fetch is delivered through the SoT reader. Observing the initial
                // Data is the causal barrier that its writer echo crossed the shared pipeline;
                // `get()` returning alone only proves the mutation's publication edge.
                val initialCollector = store.stream(TestKey("1")).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(initialCollector.awaitItem())
                val initial = assertIs<StoreResult.Data<String>>(initialCollector.awaitItem())
                assertEquals("v1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)
                assertEquals(1, calls)

                store.invalidate(TestKey("1"))
                val collector = store.stream(TestKey("1")).testIn(backgroundScope)
                val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                secondStarted.await()
                secondGate.complete(Unit)

                var terminal = collector.awaitItem()
                var queuedStaleReplays = 0
                while (terminal is StoreResult.Data<*>) {
                    // A queued pre-304 replay may survive as Data, but it must remain the stale
                    // baseline; the owner-visible fresh terminal is exclusively Revalidated.
                    queuedStaleReplays += 1
                    assertEquals(1, queuedStaleReplays, "more than one queued stale replay")
                    assertEquals("v1", terminal.value)
                    assertTrue(terminal.isStale)
                    assertTrue(terminal.refreshing)
                    terminal = collector.awaitItem()
                }
                assertIs<StoreResult.Revalidated>(terminal)
                collector.expectNoEvents()
                assertEquals(2, calls)
                initialCollector.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }
}
