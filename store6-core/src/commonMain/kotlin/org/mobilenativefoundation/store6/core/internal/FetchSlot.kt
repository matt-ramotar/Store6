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

        /** The key's clear epoch when this fetch launched; a later clear supersedes the commit. */
        val clearEpochAtLaunch: Long,
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

    /** The fetch failed with [exception] at [atEpochMillis]. */
    class Failed(
        val exception: StoreException,
        val atEpochMillis: Long,
    ) : FetchOutcome

    /** The fetch succeeded but a clear advanced the clear epoch after launch; the value was discarded. */
    data object Superseded : FetchOutcome

    /** The fetcher reported not-modified; resident metadata was refreshed in place. */
    data object Revalidated : FetchOutcome

    /** The fetcher reported server-side deletion; residence was destructively removed. */
    data object Deleted : FetchOutcome
}
