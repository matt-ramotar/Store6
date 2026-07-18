package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import org.mobilenativefoundation.store6.core.StoreException

/** Describes whether a key currently owns a fetch operation. */
internal sealed interface FetchSlot {
    /** No fetch is active for the key. */
    data object Idle : FetchSlot

    /** A fetch is active and represented by [ticket]. */
    class InFlight(
        val ticket: FetchTicket,
    ) : FetchSlot
}

/**
 * Identifies one fetch attempt and exposes its one-shot completion to all waiters.
 *
 * [outcome] reaches a terminal state either when the owning coroutine publishes its result or
 * when the parent engine begins cancellation.
 */
internal class FetchTicket(
    val outcome: CompletableDeferred<FetchOutcome>,
)

/** The terminal result of a fetch attempt. */
internal sealed interface FetchOutcome {
    /** The fetched value was committed before the outcome became observable. */
    data object Committed : FetchOutcome

    /** The fetch failed with [exception]. */
    class Failed(
        val exception: StoreException,
    ) : FetchOutcome
}
