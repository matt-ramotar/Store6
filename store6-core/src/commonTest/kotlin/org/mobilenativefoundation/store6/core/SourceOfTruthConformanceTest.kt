package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.internal.SharedFlowSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class)
class SourceOfTruthConformanceTest {

    // C-05 shape: values arriving via fetch commit are attributed FETCHER.
    @Test
    fun originHonesty_fetchCommit_emitsFetcher() = runTest {
        val store = store<TestKey, String> { fetcher { "v" } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            val data = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals(Origin.FETCHER, data.origin)
            assertFalse(data.isStale)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // C-06 shape: external data is delivered as SOT/stale before its one active-demand revalidation.
    @Test
    fun originHonesty_externalSotWrite_emitsSotToActiveStream() = runTest {
        var calls = 0
        val revalidationStarted = CompletableDeferred<Unit>()
        val revalidationGate = CompletableDeferred<Unit>()
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        val store = store<TestKey, String> {
            fetcher {
                calls++
                if (calls == 1) {
                    "fetched"
                } else {
                    revalidationStarted.complete(Unit)
                    revalidationGate.await()
                    "revalidated"
                }
            }
            persistence(sot)
        }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("fetched", assertIs<StoreResult.Data<String>>(awaitItem()).value)

            sot.write(TestKey("1"), "external")

            val external = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("external", external.value)
            assertEquals(Origin.SOT, external.origin)
            assertTrue(external.isStale)
            revalidationStarted.await()
            assertEquals(2, calls)
            revalidationGate.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // C-04 shape: the memory fast path serves without waiting for the pipeline, stamped MEMORY.
    @Test
    fun originHonesty_memoryFastPath_reStampsMemory() = runTest {
        val store = store<TestKey, String> { fetcher { "v" } }
        assertEquals("v", store.get(TestKey("1")))
        store.stream(TestKey("1")).test {
            val first = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals(Origin.MEMORY, first.origin)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // The stale-tag interleave: a collector-less commit parks a tag; a later external write must
    // not inherit it (value binding). Guards the SoT-read values-never-labeled-FETCHER criterion.
    @Test
    fun dormantCommit_externalWriteBeforeFirstCollector_attributesSot() = runTest {
        val key = TestKey("1")
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        val revalidationGate = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            fetcher {
                fetchCalls++
                if (fetchCalls == 1) {
                    "fetched"
                } else {
                    revalidationGate.await()
                    "revalidated"
                }
            }
            persistence(sot)
        }
        try {
            assertEquals("fetched", store.get(key))
            sot.write(key, "external")
            val external =
                withContext(Dispatchers.Default) {
                    store.stream(key)
                        .filterIsInstance<StoreResult.Data<String>>()
                        .first { it.value == "external" }
                }
            assertEquals(Origin.SOT, external.origin)
        } finally {
            revalidationGate.complete(Unit)
            store.close()
        }
    }

    // The F-1 killer: invalidate with multiple active collectors on the DSL default SoT.
    @Test
    fun orphanRegression_invalidateWithActiveCollectors_allObserveRefetchedData() = runTest {
        var calls = 0
        val store = store<TestKey, String> { fetcher { calls++; "v$calls" } }
        turbineScope {
            val a = store.stream(TestKey("1")).testIn(backgroundScope)
            val b = store.stream(TestKey("1")).testIn(backgroundScope)
            var aInitial = false
            while (!aInitial) {
                val item = a.awaitItem()
                if (item is StoreResult.Data<String>) {
                    assertEquals("v1", item.value)
                    aInitial = true
                } else {
                    assertIs<StoreResult.Loading>(item)
                }
            }
            var bInitial = false
            while (!bInitial) {
                val item = b.awaitItem()
                if (item is StoreResult.Data<String>) {
                    assertEquals("v1", item.value)
                    bInitial = true
                } else {
                    assertIs<StoreResult.Loading>(item)
                }
            }

            store.invalidate(TestKey("1"))

            var aSeen = false
            while (!aSeen) {
                val item = a.awaitItem()
                aSeen = item is StoreResult.Data<String> && item.value == "v2"
            }
            var bSeen = false
            while (!bSeen) {
                val item = b.awaitItem()
                bSeen = item is StoreResult.Data<String> && item.value == "v2"
            }
            a.cancelAndIgnoreRemainingEvents()
            b.cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // R7: resubscribe after clear may duplicate, never lose; cleared value never replays.
    @Test
    fun resubscribeAfterClear_duplicatesNotLosses() = runTest {
        var calls = 0
        val store = store<TestKey, String> { fetcher { calls++; "v$calls" } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }

        store.clear(TestKey("1"))

        store.stream(TestKey("1")).test {
            var sawLoading = false
            var sawFresh = false
            while (!sawFresh) {
                when (val item = awaitItem()) {
                    is StoreResult.Loading -> sawLoading = true
                    is StoreResult.Data<String> -> {
                        assertTrue(item.value != "v1")
                        if (item.value == "v2") sawFresh = true
                    }
                    else -> Unit
                }
            }
            assertTrue(sawLoading)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // Populate, unsubscribe, outlast the grace, clear, then prove stale replay cannot resurrect.
    @Test
    fun clearWhilePipelineDormant_neverResurrectsClearedValue() = runTest {
        var calls = 0
        val store = store<TestKey, String> { fetcher { calls++; "v$calls" } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }
        withContext(Dispatchers.Default) { delay(400) }

        store.clear(TestKey("1"))

        store.stream(TestKey("1")).test {
            while (true) {
                val item = awaitItem()
                if (item is StoreResult.Data<String>) {
                    assertEquals("v2", item.value)
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // Null-on-delete liveness through Store: external delete -> absent transition, stream live.
    @Test
    fun externalSotDelete_activeStreamSeesAbsentTransitionAndStaysLive() = runTest {
        var calls = 0
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        val store = store<TestKey, String> {
            fetcher { "fetched-${++calls}" }
            persistence(sot)
        }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("fetched-1", assertIs<StoreResult.Data<String>>(awaitItem()).value)

            sot.delete(TestKey("1"))

            assertIs<StoreResult.Loading>(awaitItem())
            while (true) {
                val item = awaitItem()
                if (item is StoreResult.Data) {
                    assertEquals("fetched-2", item.value)
                    assertEquals(Origin.FETCHER, item.origin)
                    assertFalse(item.isStale)
                    break
                }
            }
            assertEquals(2, calls)
            sot.write(TestKey("1"), "rewritten")
            while (true) {
                val item = awaitItem()
                if (item is StoreResult.Data && item.value == "rewritten") break
            }
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // FR-10: a pre-populated SoT serves without a fetch under LocalOnly.
    @Test
    fun localOnly_prePopulatedSot_getServesWithoutFetcher() = runTest {
        var calls = 0
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        sot.write(TestKey("1"), "durable")
        val store = store<TestKey, String> {
            fetcher { calls++; "fetched" }
            persistence(sot)
        }
        assertEquals("durable", store.get(TestKey("1"), Freshness.LocalOnly))
        assertEquals(0, calls)
        store.close()
    }

    // FS-6 + hydration: unknown provenance serves and triggers exactly one revalidation.
    @Test
    fun cachedOrFetch_hydratedRow_servesThenRevalidatesExactlyOnce() = runTest {
        var calls = 0
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        sot.write(TestKey("1"), "durable")
        val store = store<TestKey, String> {
            fetcher {
                calls++
                fetchStarted.complete(Unit)
                releaseFetch.await()
                "fetched"
            }
            persistence(sot)
        }
        try {
            assertEquals("durable", store.get(TestKey("1")))
            fetchStarted.await()
            assertEquals(1, calls)
            store.stream(TestKey("1")).test {
                val hydrated = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("durable", hydrated.value)
                assertTrue(hydrated.refreshing)

                releaseFetch.complete(Unit)
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data<String> && item.value == "fetched") break
                }
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("fetched", store.get(TestKey("1")))
            assertEquals(1, calls)
        } finally {
            releaseFetch.complete(Unit)
            store.close()
        }
    }

    // FS-1: persisted truth participates in startup before its revalidation can overwrite it.
    @Test
    fun cachedOrFetch_prePopulatedSot_streamServesSotBeforeRevalidation() = runTest {
        var calls = 0
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchGate = CompletableDeferred<Unit>()
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        sot.write(TestKey("1"), "durable")
        val store = store<TestKey, String> {
            fetcher {
                calls++
                fetchStarted.complete(Unit)
                fetchGate.await()
                "fetched"
            }
            persistence(sot)
        }
        store.stream(TestKey("1")).test {
            val durable = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("durable", durable.value)
            assertEquals(Origin.SOT, durable.origin)
            assertTrue(durable.isStale)
            assertTrue(durable.refreshing)

            fetchStarted.await()
            fetchGate.complete(Unit)
            val fetched = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("fetched", fetched.value)
            assertEquals(Origin.FETCHER, fetched.origin)
            assertEquals(1, calls)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // I7: a 304 refreshes metadata and re-emits fresh Data to the revalidating collector.
    @Test
    fun notModified_refreshesMetaAndReEmitsFreshData() = runTest {
        var calls = 0
        val store = store<TestKey, String> {
            fetcherOfResult {
                calls++
                if (calls == 1) {
                    FetcherResult.Success("v1", etag = "e1")
                } else {
                    FetcherResult.NotModified(etag = "e1")
                }
            }
        }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)

            store.invalidate(TestKey("1"))

            var seenFresh = false
            while (!seenFresh) {
                val item = awaitItem()
                if (item is StoreResult.Data<String> && !item.isStale) {
                    assertEquals("v1", item.value)
                    seenFresh = true
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // I5 enforcement: exercise every writeLock-holding path under concurrency.
    @Test
    fun lockOrderCanary_concurrentCommitClearStreamAndGet_terminates() = runTest {
        var calls = 0
        val store = store<TestKey, String> { fetcher { calls++; "v$calls" } }
        suspend fun getAllowingConcurrentClear() {
            try {
                store.get(TestKey("1"))
            } catch (failure: StoreException) {
                assertIs<StoreError.Missing>(failure.error)
            }
        }
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val collector = launch {
                    store.stream(TestKey("1")).collect { }
                }
                repeat(10) {
                    getAllowingConcurrentClear()
                    store.invalidate(TestKey("1"))
                    getAllowingConcurrentClear()
                    store.clear(TestKey("1"))
                }
                collector.cancel()
            }
        }
        store.close()
    }
}
