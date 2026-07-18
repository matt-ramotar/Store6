package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun residenceCommittedBeforeEnsureFetchLock_satisfiesRecheck() {
        val state = KeyState.Initial.copy(staleEpoch = 3L)

        assertTrue(
            residenceSatisfies(
                state = state,
                residentStaleEpochAtCommit = 3L,
            ),
        )
    }

    @Test
    fun staleOrAbsentResidence_doesNotSatisfyRecheck() {
        val state = KeyState.Initial.copy(staleEpoch = 3L)

        assertFalse(
            residenceSatisfies(
                state = state,
                residentStaleEpochAtCommit = 2L,
            ),
        )
        assertFalse(
            residenceSatisfies(
                state = state,
                residentStaleEpochAtCommit = null,
            ),
        )
    }
}
