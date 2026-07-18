package org.mobilenativefoundation.store6.core.internal

/** Immutable state governing fetch ownership for one canonical key. */
internal data class KeyState(
    /** The current fetch slot for the key. */
    val fetch: FetchSlot,
) {
    companion object {
        /** State for a key with no active fetch. */
        val Initial: KeyState = KeyState(FetchSlot.Idle)
    }
}
