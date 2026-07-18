package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
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
        val state = KeyState(FetchSlot.InFlight(existing))

        val result = transition(state, KeyEvent.EnsureFetch(ticket()))

        assertSame(state, result.state)
        assertSame(existing, assertIs<KeyEffect.Join>(result.effect).ticket)
    }

    @Test
    fun settleFetch_withMatchingTicket_returnsToIdle() {
        val current = ticket()

        val result = transition(
            KeyState(FetchSlot.InFlight(current)),
            KeyEvent.SettleFetch(current),
        )

        assertIs<FetchSlot.Idle>(result.state.fetch)
        assertIs<KeyEffect.Settled>(result.effect)
    }

    @Test
    fun settleFetch_withStaleTicket_preservesCurrentFetch() {
        val current = ticket()
        val state = KeyState(FetchSlot.InFlight(current))

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
}
