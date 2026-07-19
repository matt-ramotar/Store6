package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreMeta
import kotlin.time.Duration.Companion.milliseconds

/**
 * The value and bookkeeping facts used to plan a read.
 *
 * [status] is presently always null. Issue 006 feeds durable [KeyStatus] and watermarks into this
 * context; issue 008 freezes the post-006 shape. A resident value with null [meta] is treated as
 * conservatively stale (FS-6).
 */
internal class FreshnessContext(
    val hasResidentValue: Boolean,
    val meta: StoreMeta?,
    val epochStale: Boolean,
    val freshness: Freshness,
    val nowEpochMillis: Long,
    val status: KeyStatus? = null,
)

internal fun interface FreshnessValidator {
    fun plan(context: FreshnessContext): FetchPlan
}

internal sealed interface FetchPlan {
    data object Skip : FetchPlan

    class Fetch(
        val servesResidentWhileFetching: Boolean,
    ) : FetchPlan

    /**
     * A conditional fetch plan introduced with the planning model.
     *
     * The default validator never returns this plan, and the engine does not issue conditional
     * requests until issue 006.
     */
    class Conditional(
        val etag: String,
        val servesResidentWhileFetching: Boolean,
    ) : FetchPlan
}

internal val FetchPlan.servesResident: Boolean
    get() =
        when (this) {
            FetchPlan.Skip -> true
            is FetchPlan.Fetch -> servesResidentWhileFetching
            is FetchPlan.Conditional -> servesResidentWhileFetching
        }

/**
 * The zero-configuration AC-6 seed policy table.
 *
 * Negative wall-clock deltas are clamped to zero before evaluating [Freshness.MaxAge].
 */
internal object DefaultFreshnessValidator : FreshnessValidator {
    override fun plan(context: FreshnessContext): FetchPlan =
        when (val freshness = context.freshness) {
            Freshness.LocalOnly -> FetchPlan.Skip
            Freshness.MustBeFresh -> FetchPlan.Fetch(servesResidentWhileFetching = false)
            Freshness.CachedOrFetch,
            Freshness.StaleIfError,
            -> context.cachedOrFetchPlan()

            is Freshness.MaxAge -> context.maxAgePlan(freshness)
        }

    private fun FreshnessContext.cachedOrFetchPlan(): FetchPlan =
        if (!hasResidentValue || epochStale) {
            FetchPlan.Fetch(servesResidentWhileFetching = hasResidentValue)
        } else {
            FetchPlan.Skip
        }

    private fun FreshnessContext.maxAgePlan(freshness: Freshness.MaxAge): FetchPlan {
        if (!hasResidentValue) {
            return FetchPlan.Fetch(servesResidentWhileFetching = false)
        }

        val overAge =
            if (meta == null) {
                true
            } else {
                val elapsedMillis =
                    if (nowEpochMillis <= meta.writtenAtEpochMillis) {
                        0L
                    } else {
                        val delta = nowEpochMillis - meta.writtenAtEpochMillis
                        if (delta < 0L) Long.MAX_VALUE else delta
                    }

                elapsedMillis.milliseconds > freshness.notOlderThan
            }

        return if (epochStale || overAge) {
            FetchPlan.Fetch(servesResidentWhileFetching = false)
        } else {
            FetchPlan.Skip
        }
    }
}
