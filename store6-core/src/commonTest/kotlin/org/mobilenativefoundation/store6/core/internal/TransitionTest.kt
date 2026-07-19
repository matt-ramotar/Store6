package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class TransitionTest {
    private fun ticket(): FetchTicket = FetchTicket(CompletableDeferred())

    @Test
    fun ensureFetch_whenIdle_launchesWithFreshTicket() {
        val fresh = ticket()

        val result = transition(KeyState.Initial, KeyEvent.EnsureFetch(fresh))

        assertSame(fresh, assertIs<FetchSlot.InFlight>(result.state.fetch).ticket)
        assertSame(fresh, assertIs<KeyEffect.Launch>(result.effect).ticket)
    }

    @Test
    fun ensureFetch_whenInFlight_joinsExistingTicketWithoutChangingState() {
        val existing = ticket()
        val state =
            KeyState.Initial.copy(
                fetch = FetchSlot.InFlight(existing, clearEpochAtLaunch = 0L),
            )

        val result = transition(state, KeyEvent.EnsureFetch(ticket()))

        assertSame(state, result.state)
        assertSame(existing, assertIs<KeyEffect.Join>(result.effect).ticket)
    }

    @Test
    fun settleFetch_withMatchingTicket_returnsToIdle() {
        val current = ticket()
        val state =
            KeyState.Initial.copy(
                fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 2L),
                staleEpoch = 1L,
                clearEpoch = 2L,
            )

        val result = transition(
            state,
            KeyEvent.SettleFetch(current),
        )

        assertIs<FetchSlot.Idle>(result.state.fetch)
        assertEquals(1L, result.state.staleEpoch)
        assertEquals(2L, result.state.clearEpoch)
        assertIs<KeyEffect.Settled>(result.effect)
    }

    @Test
    fun settleFetch_withStaleTicket_preservesCurrentFetch() {
        val current = ticket()
        val state =
            KeyState.Initial.copy(
                fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 0L),
            )

        val result = transition(state, KeyEvent.SettleFetch(ticket()))

        assertSame(state, result.state)
        assertIs<KeyEffect.Ignored>(result.effect)
    }

    @Test
    fun settleFetch_whenIdle_isIgnored() {
        val result = transition(KeyState.Initial, KeyEvent.SettleFetch(ticket()))

        assertSame(KeyState.Initial, result.state)
        assertIs<KeyEffect.Ignored>(result.effect)
    }

    @Test
    fun ensureFetch_recordsClearEpochAtLaunch() {
        val state = KeyState.Initial.copy(clearEpoch = 7L)

        val result = transition(state, KeyEvent.EnsureFetch(ticket()))

        assertEquals(7L, assertIs<FetchSlot.InFlight>(result.state.fetch).clearEpochAtLaunch)
    }

    @Test
    fun commitFetch_matchingTicketAndEpoch_commitsAndSettles() {
        val current = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 0L),
            staleEpoch = 3L,
        )

        val result = transition(state, KeyEvent.CommitFetch(current))

        assertIs<KeyEffect.Commit>(result.effect)
        assertIs<FetchSlot.Idle>(result.state.fetch)
        assertEquals(3L, result.state.staleEpoch) // epochs preserved
        assertEquals(0L, result.state.clearEpoch)
    }

    @Test
    fun commitFetch_afterClearAdvancedEpoch_isSuperseded() {
        val current = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 0L),
            clearEpoch = 1L,
        )

        val result = transition(state, KeyEvent.CommitFetch(current))

        assertIs<KeyEffect.Superseded>(result.effect)
        assertSame(state, result.state) // slot kept for the settle path
    }

    @Test
    fun commitFetch_staleTicket_isIgnored() {
        val current = ticket()
        val state = KeyState.Initial.copy(fetch = FetchSlot.InFlight(current, 0L))

        val result = transition(state, KeyEvent.CommitFetch(ticket()))

        assertIs<KeyEffect.Ignored>(result.effect)
        assertSame(state, result.state)
    }

    @Test
    fun commitFetch_whenIdle_isIgnored() {
        val result = transition(KeyState.Initial, KeyEvent.CommitFetch(ticket()))

        assertIs<KeyEffect.Ignored>(result.effect)
    }

    @Test
    fun invalidate_bumpsOnlyStaleEpoch() {
        val result = transition(KeyState.Initial, KeyEvent.Invalidate)

        assertIs<KeyEffect.Invalidated>(result.effect)
        assertEquals(1L, result.state.staleEpoch)
        assertEquals(0L, result.state.clearEpoch)
        assertIs<FetchSlot.Idle>(result.state.fetch)
    }

    @Test
    fun clear_bumpsBothEpochs_andRequestsResidenceClear() {
        val result = transition(KeyState.Initial, KeyEvent.Clear)

        assertIs<KeyEffect.ClearResidence>(result.effect)
        assertEquals(1L, result.state.staleEpoch)
        assertEquals(1L, result.state.clearEpoch)
    }

    @Test
    fun settleFetch_preservesEpochs() {
        val current = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 2L),
            staleEpoch = 5L,
            clearEpoch = 2L,
        )

        val result = transition(state, KeyEvent.SettleFetch(current))

        assertIs<FetchSlot.Idle>(result.state.fetch)
        assertEquals(5L, result.state.staleEpoch) // the landed Initial-reset bug must not return
        assertEquals(2L, result.state.clearEpoch)
    }

    @Test
    fun settleFetch_afterClearAdvancedEpoch_isSupersededAndSettled() {
        val current = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 1L),
            staleEpoch = 4L,
            clearEpoch = 2L,
        )

        val result = transition(state, KeyEvent.SettleFetch(current))

        assertIs<KeyEffect.Superseded>(result.effect)
        assertIs<FetchSlot.Idle>(result.state.fetch)
        assertEquals(4L, result.state.staleEpoch)
        assertEquals(2L, result.state.clearEpoch)
    }

    @Test
    fun settleFetch_afterCommitAlreadySettled_isIgnored() {
        val current = ticket()
        val committed = transition(
            KeyState.Initial.copy(fetch = FetchSlot.InFlight(current, 0L)),
            KeyEvent.CommitFetch(current),
        )

        val result = transition(committed.state, KeyEvent.SettleFetch(current))

        assertIs<KeyEffect.Ignored>(result.effect)
        assertSame(committed.state, result.state)
    }

    @Test
    fun commitDeleted_matchingTicketAndEpoch_settlesAndRequestsDeleteCommit() {
        val owner = ticket()
        val state = KeyState.Initial.copy(fetch = FetchSlot.InFlight(owner, clearEpochAtLaunch = 0L))
        val result = transition(state, KeyEvent.CommitDeleted(owner))
        assertEquals(KeyEffect.CommitDelete, result.effect)
        assertEquals(FetchSlot.Idle, result.state.fetch)
        assertEquals(1L, result.state.clearEpoch)
        assertEquals(0L, result.state.staleEpoch) // no stale bump: deletion must not drive refetch loops
    }

    @Test
    fun commitDeleted_afterClearAdvancedEpoch_isSuperseded() {
        val owner = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(owner, clearEpochAtLaunch = 0L),
            staleEpoch = 1L,
            clearEpoch = 1L,
        )
        val result = transition(state, KeyEvent.CommitDeleted(owner))
        assertEquals(KeyEffect.Superseded, result.effect)
        assertSame(state, result.state)
    }

    @Test
    fun commitDeleted_staleTicket_isIgnored() {
        val state = KeyState.Initial.copy(fetch = FetchSlot.InFlight(ticket(), clearEpochAtLaunch = 0L))
        val result = transition(state, KeyEvent.CommitDeleted(ticket()))
        assertEquals(KeyEffect.Ignored, result.effect)
        assertSame(state, result.state)
    }

    @Test
    fun commitDeleted_whenIdle_isIgnored() {
        val result = transition(KeyState.Initial, KeyEvent.CommitDeleted(ticket()))
        assertEquals(KeyEffect.Ignored, result.effect)
        assertSame(KeyState.Initial, result.state)
    }

    @Test
    fun commitDeleted_preservesStaleEpochUnderPriorInvalidations() {
        val owner = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(owner, clearEpochAtLaunch = 2L),
            staleEpoch = 5L,
            clearEpoch = 2L,
        )
        val result = transition(state, KeyEvent.CommitDeleted(owner))
        assertEquals(KeyEffect.CommitDelete, result.effect)
        assertEquals(5L, result.state.staleEpoch)
        assertEquals(3L, result.state.clearEpoch)
    }
}
