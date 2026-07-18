package org.mobilenativefoundation.store6.core.internal

/** Immutable state governing fetch ownership and invalidation epochs for one canonical key. */
internal data class KeyState(
    /** The current fetch slot for the key. */
    val fetch: FetchSlot,

    /** Monotone count of invalidations; a resident value is stale when committed under a lower epoch. */
    val staleEpoch: Long,

    /** Monotone count of clears; guards fetch commits so a pre-clear response can never resurrect. */
    val clearEpoch: Long,
) {
    companion object {
        /** State for a key with no active fetch and no invalidation history. */
        val Initial: KeyState = KeyState(fetch = FetchSlot.Idle, staleEpoch = 0L, clearEpoch = 0L)
    }
}
