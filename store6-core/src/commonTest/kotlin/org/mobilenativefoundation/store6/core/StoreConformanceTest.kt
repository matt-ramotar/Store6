package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StoreConformanceTest {

    // (a) THE 001 acceptance test — cold stream: Loading then Data(origin=FETCHER). TEST-1 emission-sequence seed.
    @Test
    fun coldStream_noCachedValue_emitsLoadingThenDataFromFetcher() = runTest {
        val store = store<TestKey, String> { fetcher { "value-for-${it.canonicalId()}" } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            val data = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("value-for-1", data.value)
            assertEquals(Origin.FETCHER, data.origin)
            expectNoEvents() // live, not completed (FS-1)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // (b1) fetcher throws -> stream emits Error and stays live (FS-5: stream never throws)
    @Test
    fun fetcherThrows_streamEmitsErrorAndStaysLive() = runTest {
        val store = store<TestKey, String> { fetcher { throw IllegalStateException("boom") } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            val error = assertIs<StoreResult.Error>(awaitItem())
            assertIs<StoreError.Fetch>(error.error)
            expectNoEvents() // Error did not terminate the flow
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // (b2) fetcher throws -> get throws StoreException carrying StoreError.Fetch (FS-2/FS-5)
    @Test
    fun fetcherThrows_getThrowsStoreException() = runTest {
        val store = store<TestKey, String> { fetcher { throw IllegalStateException("boom") } }
        val ex = assertFailsWith<StoreException> { store.get(TestKey("1")) }
        assertIs<StoreError.Fetch>(ex.error)
        store.close()
    }

    // (c) single-flight smoke: two concurrent collectors, one fetcher invocation. C-01/C-02 seed.
    @Test
    fun twoConcurrentCollectors_singleFetcherInvocation() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                calls++
                gate.await()
                "v"
            }
        }
        turbineScope {
            val a = store.stream(TestKey("1")).testIn(backgroundScope)
            val b = store.stream(TestKey("1")).testIn(backgroundScope)
            assertIs<StoreResult.Loading>(a.awaitItem())
            assertIs<StoreResult.Loading>(b.awaitItem()) // both subscribed before the fetch resolves
            gate.complete(Unit)
            assertEquals("v", assertIs<StoreResult.Data<String>>(a.awaitItem()).value)
            assertEquals("v", assertIs<StoreResult.Data<String>>(b.awaitItem()).value)
            assertEquals(1, calls)
        }
        store.close()
    }

    // (d) pins the 001 get-posture: a resident value is served without a refetch (validator arrives in 004)
    @Test
    fun getAfterStreamCommitted_servesResidentValueWithoutRefetch() = runTest {
        var calls = 0
        val store = store<TestKey, String> {
            fetcher {
                calls++
                "v$calls"
            }
        }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertIs<StoreResult.Data<String>>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("v1", store.get(TestKey("1"))) // resident value served
        assertEquals(1, calls) // no second fetch
        store.close()
    }

    // (e) pins replay semantics: a late collector gets Data immediately, never a spurious Loading
    @Test
    fun lateCollectorAfterData_receivesDataImmediately() = runTest {
        val store = store<TestKey, String> { fetcher { "v" } }
        assertEquals("v", store.get(TestKey("1"))) // commits residence
        store.stream(TestKey("1")).test {
            val first = assertIs<StoreResult.Data<String>>(awaitItem()) // no Loading first
            assertEquals("v", first.value)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }
}
