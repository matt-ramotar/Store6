@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationsWiringSpikeTest {
    @Test
    fun spike_overlayProjectsThenWriteHandleFlipsToSot() = runTest {
        val key = MutationsTestKey("spike")
        val pending = MutableStateFlow<String?>(null)
        val signals = MutableSharedFlow<StoreKey>(replay = 1)
        val store = store<MutationsTestKey, String> {
            fetcher { "base" }
            overlay(
                object : Overlay<MutationsTestKey, String> {
                    override fun apply(
                        key: MutationsTestKey,
                        base: String?,
                    ): String? = pending.value ?: base

                    override val changes: Flow<StoreKey> = signals
                },
            )
        }

        try {
            store.stream(key).test {
                assertEquals("base", awaitData().value)

                pending.value = "base+pending"
                signals.emit(key)
                var projected = awaitData()
                while (projected.value != "base+pending") {
                    projected = awaitData()
                }
                assertEquals(Origin.OVERLAY, projected.origin)

                val writeHandle = checkNotNull(store.runtime()).writeHandle
                writeHandle.apply(key, "base+pending")
                val confirmed = awaitConfirmed()
                assertEquals("base+pending", confirmed.value)
                assertTrue(
                    confirmed.origin == Origin.SOT || confirmed.origin == Origin.MEMORY,
                    "expected SOT or MEMORY after confirmed write, was ${confirmed.origin}",
                )

                writeHandle.confirmFresh(key, etag = "srv-1")
                pending.value = null
                signals.emit(key)
                cancelAndIgnoreRemainingEvents()
            }

            store.stream(key).test {
                val retired = awaitConfirmed()
                assertEquals("base+pending", retired.value)
                assertTrue(
                    retired.origin == Origin.SOT || retired.origin == Origin.MEMORY,
                    "expected SOT or MEMORY after retirement, was ${retired.origin}",
                )
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun lastOverlayRegistrationWins() = runTest {
        val key = MutationsTestKey("last-overlay")
        val firstSignals = MutableSharedFlow<StoreKey>(replay = 1)
        val secondSignals = MutableSharedFlow<StoreKey>(replay = 1)
        val secondPending = MutableStateFlow<String?>(null)
        val firstOverlayInvoked = MutableStateFlow(false)
        val store = store<MutationsTestKey, String> {
            fetcher { "base" }
            overlay(
                object : Overlay<MutationsTestKey, String> {
                    override fun apply(
                        key: MutationsTestKey,
                        base: String?,
                    ): String? {
                        firstOverlayInvoked.value = true
                        return base
                    }

                    override val changes: Flow<StoreKey> = firstSignals
                },
            )
            overlay(
                object : Overlay<MutationsTestKey, String> {
                    override fun apply(
                        key: MutationsTestKey,
                        base: String?,
                    ): String? = base?.plus(secondPending.value.orEmpty())

                    override val changes: Flow<StoreKey> = secondSignals
                },
            )
        }

        try {
            store.stream(key).test {
                assertEquals("base", awaitData().value)
                assertFalse(firstOverlayInvoked.value)

                secondPending.value = "+second"
                secondSignals.emit(key)
                var projected = awaitData()
                while (projected.value != "base+second") {
                    projected = awaitData()
                }
                assertEquals(Origin.OVERLAY, projected.origin)
                assertFalse(firstOverlayInvoked.value)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }
}

private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
