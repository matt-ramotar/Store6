package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class StoreInvalidationConformanceTest {

    // AC-3 seed: an active stream signaled by invalidate observes refetched data.
    @Test
    fun invalidate_activeStream_observesRefetchedData() = runTest {
        var calls = 0
        val store = store<TestKey, String> { fetcher { calls++; "v$calls" } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)

            store.invalidate(TestKey("1"))

            assertEquals("v2", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // Pinned SWR posture: get on a stale resident serves stale now and refetches in background.
    @Test
    fun getOnStaleResident_servesStaleThenRefetchesInBackground() = runTest {
        var calls = 0
        val store = store<TestKey, String> { fetcher { calls++; "v$calls" } }
        assertEquals("v1", store.get(TestKey("1")))

        store.invalidate(TestKey("1"))

        assertEquals("v1", store.get(TestKey("1"))) // stale served immediately, not blocked
        store.stream(TestKey("1")).test {           // deterministically await the background commit
            while (true) {
                val item = awaitItem()
                if (item is StoreResult.Data<String> && item.value == "v2") break
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("v2", store.get(TestKey("1")))
        assertEquals(2, calls) // background refetch and stream fetch single-flighted
        store.close()
    }

    // Honesty of age / isStale / refreshing on emissions.
    @Test
    fun staleResident_newCollector_seesHonestFlagsThenFreshData() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                calls++
                if (calls > 1) gate.await()
                "v$calls"
            }
        }
        assertEquals("v1", store.get(TestKey("1")))
        store.invalidate(TestKey("1"))

        store.stream(TestKey("1")).test {
            val stale = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v1", stale.value)
            assertTrue(stale.isStale)
            assertTrue(stale.refreshing) // fetch launched inline before the replay emission
            assertTrue(stale.age >= Duration.ZERO)

            gate.complete(Unit)

            val fresh = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v2", fresh.value)
            assertFalse(fresh.isStale)
            assertFalse(fresh.refreshing) // commit settles the slot atomically
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // Clear on an active stream: absent transition (Loading), then refetched data, never stale replay.
    @Test
    fun clear_activeStream_emitsLoadingThenRefetchedData() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                calls++
                if (calls == 2) gate.await() // hold the refetch so Loading is observable
                "v$calls"
            }
        }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)

            store.clear(TestKey("1"))

            assertIs<StoreResult.Loading>(awaitItem()) // honest absent transition
            gate.complete(Unit)
            assertEquals("v2", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // C-12 seed: clear during an in-flight fetch discards the commit; no resurrection.
    @Test
    fun clearDuringInFlightFetch_commitDiscarded_noResurrection() = runTest {
        var calls = 0
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                calls++
                if (calls == 1) {
                    firstStarted.complete(Unit)
                    firstGate.await()
                    "doomed-v1"
                } else {
                    "v$calls"
                }
            }
        }
        val waiter = backgroundScope.async { runCatching { store.get(TestKey("1")) } }
        firstStarted.await()

        store.clear(TestKey("1"))
        firstGate.complete(Unit)

        val failure =
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { waiter.await() }
            }.exceptionOrNull()
        val exception = assertIs<StoreException>(failure)
        val missing = assertIs<StoreError.Missing>(exception.error)
        assertEquals("1", missing.key.canonicalId())
        assertTrue(exception.message!!.contains("test/1"))   // FS-5: which key
        assertTrue(exception.message!!.contains("clear"))    // FS-5: what happened

        assertEquals("v2", store.get(TestKey("1"))) // fresh fetch, never "doomed-v1"
        store.close()
    }

    @Test
    fun clearDuringInFlightFetch_thatFails_waiterObservesMissing() = runTest {
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchGate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                fetchStarted.complete(Unit)
                fetchGate.await()
                error("fetch failed after clear")
            }
        }
        val waiter = backgroundScope.async { runCatching { store.get(TestKey("1")) } }
        fetchStarted.await()

        store.clear(TestKey("1"))
        fetchGate.complete(Unit)

        val failure =
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { waiter.await() }
            }.exceptionOrNull()
        val exception = assertIs<StoreException>(failure)
        assertIs<StoreError.Missing>(exception.error)
        assertTrue(exception.message!!.contains("test/1"))
        assertTrue(exception.message!!.contains("clear"))
        store.close()
    }

    @Test
    fun invalidateNamespace_touchesOnlyMatchingNamespace() = runTest {
        var aCalls = 0
        var bCalls = 0
        val store = store<NamespacedTestKey, String> {
            fetcher { key ->
                if (key.namespace.value == "a") "a${++aCalls}" else "b${++bCalls}"
            }
        }
        assertEquals("a1", store.get(NamespacedTestKey("a", "1")))
        assertEquals("b1", store.get(NamespacedTestKey("b", "1")))

        store.invalidateNamespace(StoreNamespace("a"))

        assertEquals("b1", store.get(NamespacedTestKey("b", "1"))) // untouched, no refetch
        assertEquals(1, bCalls)
        assertEquals("a1", store.get(NamespacedTestKey("a", "1"))) // stale served, refetch fired
        store.stream(NamespacedTestKey("a", "1")).test {
            while (true) {
                val item = awaitItem()
                if (item is StoreResult.Data<String> && item.value == "a2") break
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, aCalls)
        store.close()
    }

    @Test
    fun clearAll_dropsResidenceForEveryKey() = runTest {
        var calls = 0
        val store = store<NamespacedTestKey, String> { fetcher { "v${++calls}" } }
        assertEquals("v1", store.get(NamespacedTestKey("a", "1")))
        assertEquals("v2", store.get(NamespacedTestKey("b", "2")))

        store.clearAll()

        // Residence is gone: both keys refetch.
        assertEquals("v3", store.get(NamespacedTestKey("a", "1")))
        assertEquals("v4", store.get(NamespacedTestKey("b", "2")))
        store.close()
    }

    @Test
    fun maintenanceAfterClose_failsFastWithDeterministicException() = runTest {
        val store = store<TestKey, String> { fetcher { "v" } }
        store.close()

        assertEquals(
            "Store is closed.",
            assertFailsWith<IllegalStateException> { store.invalidate(TestKey("1")) }.message,
        )
        assertEquals(
            "Store is closed.",
            assertFailsWith<IllegalStateException> { store.clearAll() }.message,
        )
    }
}
