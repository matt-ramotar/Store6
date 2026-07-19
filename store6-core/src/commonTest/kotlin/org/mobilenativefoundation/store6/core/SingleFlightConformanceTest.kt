package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SingleFlightConformanceTest {
    @Test
    fun ac2_fiftyGettersAndFiftyCollectorsShareOneFetch() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                calls++
                started.complete(Unit)
                gate.await()
                "v"
            }
        }
        val key = TestKey("1")

        try {
            val getters =
                List(50) {
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        store.get(key)
                    }
                }
            val collectors =
                List(50) {
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        val item = store.stream(key).first { it is StoreResult.Data<*> }
                        assertIs<StoreResult.Data<String>>(item).value
                    }
                }

            started.await()
            assertEquals(1, calls)
            gate.complete(Unit)

            assertTrue(getters.awaitAll().all { it == "v" })
            assertTrue(collectors.awaitAll().all { it == "v" })
            assertEquals(1, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun cancelledWaiterDoesNotCancelSharedFetch() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                calls++
                started.complete(Unit)
                gate.await()
                "v"
            }
        }
        val key = TestKey("1")

        try {
            val cancelled =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(key)
                }
            started.await()
            cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)

            val later =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(key)
                }
            gate.complete(Unit)

            assertEquals("v", later.await())
            assertEquals("v", store.get(key))
            assertEquals(1, calls)
        } finally {
            store.close()
        }
    }
}
