package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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

open class StoreInvalidationConformanceTest : SourceOfTruthSubstitutionTest() {
    // AC-3 (TEST-1): an active stream signaled by invalidate observes refetched data.
    @Test
    fun invalidate_activeStream_observesRefetchedData() = runTest {
        var calls = 0
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        val key = TestKey("1")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> {
                        firstFetchStarted.complete(Unit)
                        releaseFirstFetch.await()
                        "v1"
                    }
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                firstFetchStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(1, calls)
                releaseFirstFetch.complete(Unit)
                val initial = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)

                store.invalidate(key)
                secondFetchStarted.awaitFromDefault()
                releaseSecondFetch.complete(Unit)

                var fresh = assertIs<StoreResult.Data<String>>(awaitItem())
                var queuedStaleReplays = 0
                while (fresh.value == "v1") {
                    queuedStaleReplays += 1
                    assertEquals(1, queuedStaleReplays, "more than one queued stale replay")
                    assertTrue(fresh.isStale)
                    assertTrue(fresh.refreshing)
                    fresh = assertIs<StoreResult.Data<String>>(awaitItem())
                }
                assertEquals("v2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                expectNoEvents()
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseFirstFetch.complete(Unit)
            releaseSecondFetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    // Pinned SWR posture: get on a stale resident serves stale now and refetches in background.
    @Test
    fun getOnStaleResident_servesStaleThenRefetchesInBackground() = runTest {
        var calls = 0
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        val key = TestKey("1")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> {
                        firstFetchStarted.complete(Unit)
                        releaseFirstFetch.await()
                        "v1"
                    }
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }
        try {
            turbineScope {
                val initialCollector = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(initialCollector.awaitItem())
                firstFetchStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(1, calls)
                releaseFirstFetch.complete(Unit)
                val initial = assertIs<StoreResult.Data<String>>(initialCollector.awaitItem())
                assertEquals("v1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)

                store.invalidate(key)

                assertEquals("v1", store.get(key)) // stale served immediately, not blocked
                secondFetchStarted.awaitFromDefault()
                val collector = store.stream(key).testIn(backgroundScope)
                val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                releaseSecondFetch.complete(Unit)
                var fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                var queuedStaleReplays = 0
                while (fresh.value == "v1") {
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
                initialCollector.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
            assertEquals("v2", store.get(key))
            assertEquals(2, calls) // background refetch and stream fetch single-flighted
        } finally {
            releaseFirstFetch.complete(Unit)
            releaseSecondFetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    // Honesty of age / isStale / refreshing on emissions.
    @Test
    fun staleResident_newCollector_seesHonestFlagsThenFreshData() = runTest {
        var calls = 0
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> {
                        firstStarted.complete(Unit)
                        firstGate.await()
                        "v1"
                    }
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
                val initialCollector = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(initialCollector.awaitItem())
                firstStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(1, calls)
                firstGate.complete(Unit)
                val initial = assertIs<StoreResult.Data<String>>(initialCollector.awaitItem())
                assertEquals("v1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)

                store.invalidate(key)
                secondStarted.awaitFromDefault()

                val collector = store.stream(key).testIn(backgroundScope)
                val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)
                assertTrue(stale.age >= Duration.ZERO)

                secondGate.complete(Unit)

                var fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                var queuedStaleReplays = 0
                while (fresh.value == "v1") {
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
            firstGate.complete(Unit)
            secondGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    // Clear on an active stream: absent transition (Loading), then refetched data, never stale replay.
    @Test
    fun clear_activeStream_emitsLoadingThenRefetchedData() = runTest {
        var calls = 0
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        val key = TestKey("1")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> {
                        firstFetchStarted.complete(Unit)
                        releaseFirstFetch.await()
                        "v1"
                    }
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await() // hold the refetch so Loading is observable
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                firstFetchStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(1, calls)
                releaseFirstFetch.complete(Unit)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)

                store.clear(key)

                assertIs<StoreResult.Loading>(awaitItem()) // honest absent transition
                secondFetchStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(2, calls)
                releaseSecondFetch.complete(Unit)
                assertEquals("v2", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                expectNoEvents()
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseFirstFetch.complete(Unit)
            releaseSecondFetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    // C-12 seed: clear during an in-flight fetch discards the commit; no resurrection.
    @Test
    fun clearDuringInFlightFetch_commitDiscarded_noResurrection() = runTest {
        var calls = 0
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
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

        try {
            val waiter = backgroundScope.async { runCatching { store.get(TestKey("1")) } }
            firstStarted.awaitFromDefault()

            store.clear(TestKey("1"))
            firstGate.complete(Unit)

            val failure =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { waiter.await() }
                }.exceptionOrNull()
            val exception = assertIs<StoreException>(failure)
            val missing = assertIs<StoreError.Missing>(exception.error)
            assertEquals("1", missing.key.canonicalId())
            assertTrue(exception.message!!.contains("test/1")) // FS-5: which key
            assertTrue(exception.message!!.contains("clear")) // FS-5: what happened

            assertEquals("v2", store.get(TestKey("1"))) // fresh fetch, never "doomed-v1"
        } finally {
            firstGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun clearDuringInFlightFetch_thatFails_waiterObservesMissing() = runTest {
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                fetchStarted.complete(Unit)
                fetchGate.await()
                error("fetch failed after clear")
            }
        }

        try {
            val waiter = backgroundScope.async { runCatching { store.get(TestKey("1")) } }
            fetchStarted.awaitFromDefault()

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
        } finally {
            fetchGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun invalidateNamespace_touchesOnlyMatchingNamespace() = runTest {
        var aCalls = 0
        var bCalls = 0
        val a1Started = CompletableDeferred<Unit>()
        val a1Gate = CompletableDeferred<Unit>()
        val a2Started = CompletableDeferred<Unit>()
        val a2Gate = CompletableDeferred<Unit>()
        val keyA = NamespacedTestKey("a", "1")
        val keyB = NamespacedTestKey("b", "1")
        val store = testStore<NamespacedTestKey, String> {
            fetcher { key ->
                if (key.namespace.value == "a") {
                    when (++aCalls) {
                        1 -> {
                            a1Started.complete(Unit)
                            a1Gate.await()
                            "a1"
                        }
                        2 -> {
                            a2Started.complete(Unit)
                            a2Gate.await()
                            "a2"
                        }
                        else -> error("unexpected namespace-a fetch call $aCalls")
                    }
                } else {
                    when (++bCalls) {
                        1 -> "b1"
                        else -> error("unexpected namespace-b fetch call $bCalls")
                    }
                }
            }
        }

        try {
            turbineScope {
                val initialCollector = store.stream(keyA).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(initialCollector.awaitItem())
                a1Started.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(keyA)
                assertEquals(1, aCalls)
                a1Gate.complete(Unit)
                val initial = assertIs<StoreResult.Data<String>>(initialCollector.awaitItem())
                assertEquals("a1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)
                assertEquals("b1", store.get(keyB))

                store.invalidateNamespace(StoreNamespace("a"))
                a2Started.awaitFromDefault()

                assertEquals("b1", store.get(keyB)) // untouched, no refetch
                assertEquals(1, bCalls)
                assertEquals("a1", store.get(keyA)) // stale served immediately

                val collector = store.stream(keyA).testIn(backgroundScope)
                val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("a1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)
                a2Gate.complete(Unit)
                var fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                var queuedStaleReplays = 0
                while (fresh.value == "a1") {
                    queuedStaleReplays += 1
                    assertEquals(1, queuedStaleReplays, "more than one queued stale replay")
                    assertTrue(fresh.isStale)
                    assertTrue(fresh.refreshing)
                    fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                }
                assertEquals("a2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                collector.expectNoEvents()
                initialCollector.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, aCalls)
        } finally {
            a1Gate.complete(Unit)
            a2Gate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun invalidateNamespace_wakesOnlyMatchingResidentCollector() = runTest {
        var aCalls = 0
        var bCalls = 0
        val aInitialStarted = CompletableDeferred<Unit>()
        val releaseAInitial = CompletableDeferred<Unit>()
        val bInitialStarted = CompletableDeferred<Unit>()
        val releaseBInitial = CompletableDeferred<Unit>()
        val aRefreshStarted = CompletableDeferred<Unit>()
        val releaseARefresh = CompletableDeferred<Unit>()
        val keyA = NamespacedTestKey("a", "1")
        val keyB = NamespacedTestKey("b", "1")
        val store = testStore<NamespacedTestKey, String> {
            fetcher { key ->
                if (key.namespace.value == "a") {
                    when (++aCalls) {
                        1 -> {
                            aInitialStarted.complete(Unit)
                            releaseAInitial.await()
                            "a1"
                        }
                        2 -> {
                            aRefreshStarted.complete(Unit)
                            releaseARefresh.await()
                            "a2"
                        }
                        else -> error("unexpected namespace-a fetch call $aCalls")
                    }
                } else {
                    when (++bCalls) {
                        1 -> {
                            bInitialStarted.complete(Unit)
                            releaseBInitial.await()
                            "b1"
                        }
                        else -> error("unexpected namespace-b fetch call $bCalls")
                    }
                }
            }
        }

        try {
            turbineScope {
                val aCollector = store.stream(keyA).testIn(backgroundScope)
                val bCollector = store.stream(keyB).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(aCollector.awaitItem())
                aInitialStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(keyA)
                assertEquals(1, aCalls)
                releaseAInitial.complete(Unit)
                assertEquals("a1", assertIs<StoreResult.Data<String>>(aCollector.awaitItem()).value)
                assertIs<StoreResult.Loading>(bCollector.awaitItem())
                bInitialStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(keyB)
                assertEquals(1, bCalls)
                releaseBInitial.complete(Unit)
                assertEquals("b1", assertIs<StoreResult.Data<String>>(bCollector.awaitItem()).value)

                store.invalidateNamespace(StoreNamespace("a"))
                aRefreshStarted.awaitFromDefault()
                bCollector.expectNoEvents()
                assertEquals(1, bCalls)

                releaseARefresh.complete(Unit)
                while (true) {
                    val item = aCollector.awaitItem()
                    if (item is StoreResult.Data<String> && item.value == "a2") break
                }
                bCollector.expectNoEvents()
                assertEquals(2, aCalls)
                assertEquals(1, bCalls)
                aCollector.cancelAndIgnoreRemainingEvents()
                bCollector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseAInitial.complete(Unit)
            releaseBInitial.complete(Unit)
            releaseARefresh.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun invalidateAll_wakesResidentCollector() = runTest {
        var calls = 0
        val initialStarted = CompletableDeferred<Unit>()
        val releaseInitial = CompletableDeferred<Unit>()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> {
                        initialStarted.complete(Unit)
                        releaseInitial.await()
                        "v1"
                    }
                    2 -> {
                        refreshStarted.complete(Unit)
                        releaseRefresh.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            val key = TestKey("1")
            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                initialStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(1, calls)
                releaseInitial.complete(Unit)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)

                store.invalidateAll()
                refreshStarted.awaitFromDefault()
                releaseRefresh.complete(Unit)
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data<String> && item.value == "v2") break
                }
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseInitial.complete(Unit)
            releaseRefresh.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun clearNamespace_deletesAffectedRowsAndKeepsOtherNamespace() = runTest {
        var calls = 0
        val keyA = NamespacedTestKey("a", "1")
        val keyB = NamespacedTestKey("b", "1")
        val store = testStore<NamespacedTestKey, String> { fetcher { "v${++calls}" } }

        try {
            assertEquals("v1", store.get(keyA))
            assertEquals("v2", store.get(keyB))
            store.clearNamespace(StoreNamespace("a"))

            assertEquals("v2", store.get(keyB, Freshness.LocalOnly))
            val missing = assertFailsWith<StoreException> {
                store.get(keyA, Freshness.LocalOnly)
            }
            assertIs<StoreError.Missing>(missing.error)
            assertEquals("v3", store.get(keyA))
            assertEquals(3, calls)
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun clear_thenNewStreamEmitsLoadingNeverStaleReplay() = runTest {
        var calls = 0
        val refetchStarted = CompletableDeferred<Unit>()
        val releaseRefetch = CompletableDeferred<Unit>()
        val key = TestKey("1")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        refetchStarted.complete(Unit)
                        releaseRefetch.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            store.clear(key)
            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                refetchStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(key)
                releaseRefetch.complete(Unit)
                awaitDataValue(expected = "v2")
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, calls)
        } finally {
            releaseRefetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clearNamespace_thenNewStreamEmitsLoadingNeverPreClearData() = runTest {
        var calls = 0
        val refetchStarted = CompletableDeferred<Unit>()
        val releaseRefetch = CompletableDeferred<Unit>()
        val key = NamespacedTestKey("a", "1")
        val store = testStore<NamespacedTestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        refetchStarted.complete(Unit)
                        releaseRefetch.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            turbineScope {
                val retainedCollector =
                    store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                val retained = assertIs<StoreResult.Data<String>>(retainedCollector.awaitItem())
                assertEquals("v1", retained.value)
                assertFalse(retained.isStale)
                assertFalse(retained.refreshing)
                runCurrent()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(1, calls)

                store.clearNamespace(StoreNamespace("a"))
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(1, calls)
                val collector = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(collector.awaitItem())
                refetchStarted.awaitFromDefault()
                // The new collector's initial Loading is sent before StreamDelivery.start installs
                // its ticket watcher. Drain that continuation while fetch 2 remains gated so its
                // post-clear demand is enrolled before the shared outcome can settle.
                runCurrent()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(2, calls)
                releaseRefetch.complete(Unit)
                val fresh = collector.awaitFreshDataAfterClear(forbidden = "v1")
                assertEquals("v2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                collector.expectNoEvents()
                retainedCollector.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, calls)
        } finally {
            releaseRefetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun clearAll_dropsResidenceForEveryKey() = runTest {
        var calls = 0
        val store = testStore<NamespacedTestKey, String> { fetcher { "v${++calls}" } }

        try {
            assertEquals("v1", store.get(NamespacedTestKey("a", "1")))
            assertEquals("v2", store.get(NamespacedTestKey("b", "2")))

            store.clearAll()

            // Residence is gone: both keys refetch.
            assertEquals("v3", store.get(NamespacedTestKey("a", "1")))
            assertEquals("v4", store.get(NamespacedTestKey("b", "2")))
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun maintenanceAfterClose_failsFastWithDeterministicException() = runTest {
        val store = testStore<TestKey, String> { fetcher { "v" } }
        try {
            store.close()

            assertEquals(
                "Store is closed.",
                assertFailsWith<IllegalStateException> { store.invalidate(TestKey("1")) }.message,
            )
            assertEquals(
                "Store is closed.",
                assertFailsWith<IllegalStateException> { store.clearAll() }.message,
            )
        } finally {
            store.closeAndSettleForTest()
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<StoreResult<String>>.awaitDataValue(
        expected: String,
    ) {
        while (true) {
            when (val item = awaitItem()) {
                is StoreResult.Data -> {
                    assertEquals(expected, item.value, "pre-clear Data must never replay")
                    return
                }
                is StoreResult.Loading -> Unit
                is StoreResult.Error -> {
                    val cause = (item.error as? StoreError.Fetch)?.cause
                    throw AssertionError(
                        "unexpected clear-cycle error: ${item.error}; cause=${cause?.message}",
                        cause,
                    )
                }
                is StoreResult.Revalidated -> throw AssertionError("clear must not revalidate")
            }
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<StoreResult<String>>.awaitFreshDataAfterClear(
        forbidden: String,
    ): StoreResult.Data<String> {
        while (true) {
            when (val item = awaitItem()) {
                is StoreResult.Data -> {
                    assertTrue(item.value != forbidden, "pre-clear Data must never replay")
                    if (!item.isStale && !item.refreshing) return item
                }
                is StoreResult.Loading -> Unit
                is StoreResult.Error -> {
                    val cause = (item.error as? StoreError.Fetch)?.cause
                    throw AssertionError(
                        "unexpected clear-cycle error: ${item.error}; cause=${cause?.message}",
                        cause,
                    )
                }
                is StoreResult.Revalidated -> throw AssertionError("clear must not revalidate")
            }
        }
    }
}

private suspend fun <T> CompletableDeferred<T>.awaitFromDefault(): T =
    withContext(Dispatchers.Default) {
        withTimeout(5_000) { await() }
    }
