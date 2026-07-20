package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreMeta
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The value and bookkeeping facts used to plan a read.
 *
 * [status] carries the durable bookkeeping posture captured before the corresponding engine-state
 * snapshot. A resident value with null [meta] is treated as conservatively stale (FS-6).
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
     * A conditional fetch plan selected when an unsatisfied resident has a reusable [etag].
     *
     * The ETag remains planning metadata in issue 006; transport through the public fetcher seam
     * arrives with issue 008.
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
 * Returns elapsed age with the ratified wall-clock posture: missing metadata and backward clocks
 * are zero, while positive subtraction overflow saturates to [Long.MAX_VALUE] milliseconds.
 */
internal fun elapsedAge(
    nowEpochMillis: Long,
    meta: StoreMeta?,
): Duration {
    if (meta == null) return Duration.ZERO
    val writtenAtEpochMillis = meta.writtenAtEpochMillis
    val elapsedMillis =
        if (nowEpochMillis <= writtenAtEpochMillis) {
            0L
        } else {
            val delta = nowEpochMillis - writtenAtEpochMillis
            if (delta < 0L) Long.MAX_VALUE else delta
        }
    return elapsedMillis.milliseconds
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
            Freshness.MustBeFresh ->
                context.fetchPlan(servesResidentWhileFetching = false)
            Freshness.CachedOrFetch,
            Freshness.StaleIfError,
            -> context.cachedOrFetchPlan()

            is Freshness.MaxAge -> context.maxAgePlan(freshness)
        }

    private fun FreshnessContext.cachedOrFetchPlan(): FetchPlan =
        if (
            !hasResidentValue ||
            meta == null ||
            epochStale ||
            status?.durablyStale == true
        ) {
            fetchPlan(servesResidentWhileFetching = hasResidentValue)
        } else {
            FetchPlan.Skip
        }

    private fun FreshnessContext.maxAgePlan(freshness: Freshness.MaxAge): FetchPlan {
        if (!hasResidentValue) {
            return fetchPlan(servesResidentWhileFetching = false)
        }

        val overAge = meta == null || elapsedAge(nowEpochMillis, meta) > freshness.notOlderThan

        return if (epochStale || status?.durablyStale == true || overAge) {
            fetchPlan(servesResidentWhileFetching = false)
        } else {
            FetchPlan.Skip
        }
    }

    private fun FreshnessContext.fetchPlan(servesResidentWhileFetching: Boolean): FetchPlan {
        val etag = meta?.etag
        return if (hasResidentValue && etag != null) {
            FetchPlan.Conditional(etag, servesResidentWhileFetching)
        } else {
            FetchPlan.Fetch(servesResidentWhileFetching)
        }
    }
}
