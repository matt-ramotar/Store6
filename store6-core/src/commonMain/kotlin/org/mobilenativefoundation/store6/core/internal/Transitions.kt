package org.mobilenativefoundation.store6.core.internal

/** An input applied to the immutable state for a canonical key. */
internal sealed interface KeyEvent {
    /** Requests a fetch, offering [fresh] as the ticket if no fetch is active. */
    class EnsureFetch(
        val fresh: FetchTicket,
    ) : KeyEvent

    /** Reports that the fetch represented by [ticket] reached a terminal outcome. */
    class SettleFetch(
        val ticket: FetchTicket,
    ) : KeyEvent
}

/** A side effect for the engine to interpret after a pure state transition. */
internal sealed interface KeyEffect {
    /** Launch a fetch owned by [ticket]. */
    class Launch(
        val ticket: FetchTicket,
    ) : KeyEffect

    /** Wait for the existing fetch represented by [ticket]. */
    class Join(
        val ticket: FetchTicket,
    ) : KeyEffect

    /** The matching active fetch was settled. */
    data object Settled : KeyEffect

    /** The event did not apply to the current state. */
    data object Ignored : KeyEffect
}

/** The immutable state and engine effect produced by applying one [KeyEvent]. */
internal data class KeyTransition(
    val state: KeyState,
    val effect: KeyEffect,
)

/**
 * Applies [event] to [state] without performing suspension, I/O, or coroutine work.
 *
 * A settle event changes state only when its ticket is the same instance as the active ticket.
 * This identity check prevents an older fetch from settling a newer fetch slot.
 */
internal fun transition(
    state: KeyState,
    event: KeyEvent,
): KeyTransition =
    when (event) {
        is KeyEvent.EnsureFetch ->
            when (val slot = state.fetch) {
                FetchSlot.Idle ->
                    KeyTransition(
                        state = KeyState(FetchSlot.InFlight(event.fresh)),
                        effect = KeyEffect.Launch(event.fresh),
                    )

                is FetchSlot.InFlight ->
                    KeyTransition(
                        state = state,
                        effect = KeyEffect.Join(slot.ticket),
                    )
            }

        is KeyEvent.SettleFetch ->
            when (val slot = state.fetch) {
                is FetchSlot.InFlight ->
                    if (slot.ticket === event.ticket) {
                        KeyTransition(
                            state = KeyState.Initial,
                            effect = KeyEffect.Settled,
                        )
                    } else {
                        KeyTransition(
                            state = state,
                            effect = KeyEffect.Ignored,
                        )
                    }

                FetchSlot.Idle ->
                    KeyTransition(
                        state = state,
                        effect = KeyEffect.Ignored,
                    )
            }
    }
