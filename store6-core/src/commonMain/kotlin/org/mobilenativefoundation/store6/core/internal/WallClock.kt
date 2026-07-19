package org.mobilenativefoundation.store6.core.internal

/**
 * Supplies wall-clock time only for age and bounds calculations.
 *
 * The store-global monotone success sequence handles ordering. This clock seam is a freeze
 * candidate for issue 008.
 */
internal fun interface WallClock {
    /** The current wall-clock time in milliseconds since the Unix epoch. */
    fun nowEpochMillis(): Long
}

/** The production clock backed by each platform's system clock. */
internal val SystemWallClock: WallClock = WallClock { currentEpochMillis() }

/** Reads the platform's wall clock in milliseconds since the Unix epoch. */
internal expect fun currentEpochMillis(): Long
