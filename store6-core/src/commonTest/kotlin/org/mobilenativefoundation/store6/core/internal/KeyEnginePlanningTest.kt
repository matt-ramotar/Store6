package org.mobilenativefoundation.store6.core.internal

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.FakeWallClock
import org.mobilenativefoundation.store6.core.FetcherResult
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.TestKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class KeyEnginePlanningTest {
    @Test
    fun staleEpochAdvancedBeforeSubscription_isReplayedAfterPlanningEpoch() = runTest {
        val states = MutableStateFlow(KeyState.Initial)
        val planningEpoch = states.value.staleEpoch
        states.value = states.value.copy(staleEpoch = planningEpoch + 1L)

        assertEquals(
            planningEpoch + 1L,
            states.staleEpochsAfter(planningEpoch).first(),
        )
    }

    @Test
    fun mustBeFreshInvalidationDuringSuccessfulInitialFetch_isSatisfiedByCommit() = runTest {
        val key = TestKey("1")
        var calls = 0
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = {
                    when (++calls) {
                        1 -> {
                            firstStarted.complete(Unit)
                            firstGate.await()
                            FetcherResult.Success("v1")
                        }

                        2 -> {
                            secondStarted.complete(Unit)
                            FetcherResult.Success("v2")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        engine.stream(Freshness.MustBeFresh).test {
            assertIs<StoreResult.Loading>(awaitItem())
            firstStarted.await()
            engine.invalidate()
            firstGate.complete(Unit)

            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            testScheduler.runCurrent()
            assertEquals(1, calls)
            assertFalse(secondStarted.isCompleted)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
