package org.mobilenativefoundation.store6.core.internal

/** An input applied to the immutable state for a canonical key. */
internal sealed interface KeyEvent {
    /** Requests a fetch, offering [fresh] as the ticket if no fetch is active. */
    class EnsureFetch(
        val fresh: FetchTicket,
    ) : KeyEvent

    /** Reports that the fetch represented by [ticket] produced a value ready to commit. */
    class CommitFetch(
        val ticket: FetchTicket,
    ) : KeyEvent

    /** Reports that the fetch represented by [ticket] observed a server-side deletion. */
    class CommitDeleted(
        val ticket: FetchTicket,
    ) : KeyEvent

    /** Reports that the fetch represented by [ticket] reached a terminal outcome. */
    class SettleFetch(
        val ticket: FetchTicket,
    ) : KeyEvent

    /** Marks the key stale without removing its value. */
    data object Invalidate : KeyEvent

    /** Destructively removes the key's value and supersedes any in-flight fetch commit. */
    data object Clear : KeyEvent
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

    /** Assign the fetched value to residence inside the same critical section. */
    data object Commit : KeyEffect

    /** Null out residence and forget bookkeeping: the server deleted the value. */
    data object CommitDelete : KeyEffect

    /** The fetch result was rejected because a clear advanced the clear epoch after launch. */
    data object Superseded : KeyEffect

    /** The key was marked stale; no residence change is required. */
    data object Invalidated : KeyEffect

    /** Null out residence inside the same critical section. */
    data object ClearResidence : KeyEffect

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
 * Ticket comparisons are identity checks so an older fetch can never commit into or settle a
 * newer fetch's slot. A successful commit also settles the slot: with no I/O between the commit
 * decision and residence assignment there is nothing left for the fetch to do, and settling
 * atomically makes the refreshing flag of the post-commit emission deterministic. Epoch fields
 * are monotone and are never reset by any event.
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
                        state = state.copy(
                            fetch = FetchSlot.InFlight(
                                ticket = event.fresh,
                                clearEpochAtLaunch = state.clearEpoch,
                            ),
                        ),
                        effect = KeyEffect.Launch(event.fresh),
                    )

                is FetchSlot.InFlight ->
                    KeyTransition(state = state, effect = KeyEffect.Join(slot.ticket))
            }

        is KeyEvent.CommitFetch ->
            when (val slot = state.fetch) {
                is FetchSlot.InFlight ->
                    when {
                        slot.ticket !== event.ticket ->
                            KeyTransition(state = state, effect = KeyEffect.Ignored)

                        slot.clearEpochAtLaunch != state.clearEpoch ->
                            KeyTransition(state = state, effect = KeyEffect.Superseded)

                        else ->
                            KeyTransition(
                                state = state.copy(fetch = FetchSlot.Idle),
                                effect = KeyEffect.Commit,
                            )
                    }

                FetchSlot.Idle ->
                    KeyTransition(state = state, effect = KeyEffect.Ignored)
            }

        is KeyEvent.CommitDeleted ->
            when (val slot = state.fetch) {
                is FetchSlot.InFlight ->
                    when {
                        slot.ticket !== event.ticket ->
                            KeyTransition(state = state, effect = KeyEffect.Ignored)

                        slot.clearEpochAtLaunch != state.clearEpoch ->
                            KeyTransition(state = state, effect = KeyEffect.Superseded)

                        else ->
                            KeyTransition(
                                // staleEpoch deliberately unchanged: a deleted key is absent-and-
                                // satisfied, so streams are not driven into a refetch loop; the
                                // next demand fetches. clearEpoch advances because the removal is
                                // destructive.
                                state = state.copy(
                                    fetch = FetchSlot.Idle,
                                    clearEpoch = state.clearEpoch + 1,
                                ),
                                effect = KeyEffect.CommitDelete,
                            )
                    }

                FetchSlot.Idle ->
                    KeyTransition(state = state, effect = KeyEffect.Ignored)
            }

        is KeyEvent.SettleFetch ->
            when (val slot = state.fetch) {
                is FetchSlot.InFlight ->
                    if (slot.ticket === event.ticket) {
                        KeyTransition(
                            state = state.copy(fetch = FetchSlot.Idle),
                            effect =
                                if (slot.clearEpochAtLaunch != state.clearEpoch) {
                                    KeyEffect.Superseded
                                } else {
                                    KeyEffect.Settled
                                },
                        )
                    } else {
                        KeyTransition(state = state, effect = KeyEffect.Ignored)
                    }

                FetchSlot.Idle ->
                    KeyTransition(state = state, effect = KeyEffect.Ignored)
            }

        KeyEvent.Invalidate ->
            KeyTransition(
                state = state.copy(staleEpoch = state.staleEpoch + 1),
                effect = KeyEffect.Invalidated,
            )

        KeyEvent.Clear ->
            KeyTransition(
                state = state.copy(
                    clearEpoch = state.clearEpoch + 1,
                    staleEpoch = state.staleEpoch + 1,
                ),
                effect = KeyEffect.ClearResidence,
            )
    }
