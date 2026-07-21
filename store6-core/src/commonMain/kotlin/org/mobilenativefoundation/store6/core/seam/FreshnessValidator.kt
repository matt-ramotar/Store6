package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreMeta

/**
 * The value and bookkeeping facts used to plan a read.
 *
 * [status] carries the durable bookkeeping posture captured before the corresponding engine-state
 * snapshot. A resident value with null [meta] is treated as conservatively stale.
 *
 * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then.
 */
@ExperimentalStoreApi
public class FreshnessContext(
    public val hasResidentValue: Boolean,
    public val meta: StoreMeta?,
    public val epochStale: Boolean,
    public val freshness: Freshness,
    public val nowEpochMillis: Long,
    public val status: KeyStatus? = null,
)

/**
 * Selects the fetch plan for one coherent [FreshnessContext].
 *
 * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then.
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface FreshnessValidator {
    /** Plans whether and how the current read should fetch as a pure function of [context]. */
    public fun plan(context: FreshnessContext): FetchPlan
}

/**
 * Fetch action selected by a [FreshnessValidator].
 *
 * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then.
 */
@ExperimentalStoreApi
public sealed interface FetchPlan {
    /**
     * Skips fetching. Skip with no resident value yields [StoreError.Missing] (get throws, stream
     * emits Error).
     *
     * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then.
     */
    public data object Skip : FetchPlan

    /**
     * Performs an unconditional fetch and optionally serves the resident value while it runs.
     *
     * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then.
     */
    public class Fetch(
        public val servesResidentWhileFetching: Boolean,
    ) : FetchPlan

    /**
     * Performs a conditional fetch for [etag] and optionally serves residence while it runs.
     *
     * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then.
     */
    public class Conditional(
        public val etag: String,
        public val servesResidentWhileFetching: Boolean,
    ) : FetchPlan
}
