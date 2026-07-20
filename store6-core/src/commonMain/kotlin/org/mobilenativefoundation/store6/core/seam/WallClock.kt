package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Supplies wall-clock time only for age and bounds calculations. Implementations must be cheap and
 * non-blocking.
 *
 * The store-global monotone success sequence handles ordering; implementations must not use wall
 * time as an ordering substitute.
 *
 * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then.
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface WallClock {
    /** Returns the current wall-clock time in milliseconds since the Unix epoch. */
    public fun nowEpochMillis(): Long
}
