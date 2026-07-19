package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Freshness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class FreshnessValidatorTest {
    @Test
    fun localOnly_alwaysSkipsAbsentAndStaleResident() {
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = false,
                meta = null,
                epochStale = false,
                freshness = Freshness.LocalOnly,
            ),
        )
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = true,
                freshness = Freshness.LocalOnly,
            ),
        )
    }

    @Test
    fun mustBeFresh_alwaysFetchesWithoutServingResident() {
        assertFetch(
            plan(
                hasResidentValue = false,
                meta = null,
                epochStale = false,
                freshness = Freshness.MustBeFresh,
            ),
            servesResident = false,
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = true,
                freshness = Freshness.MustBeFresh,
            ),
            servesResident = false,
        )
    }

    @Test
    fun cachedOrFetch_skipsFreshFetchesStaleResidentAndFetchesAbsent() {
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = false,
                freshness = Freshness.CachedOrFetch,
            ),
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = true,
                freshness = Freshness.CachedOrFetch,
            ),
            servesResident = true,
        )
        assertFetch(
            plan(
                hasResidentValue = false,
                meta = null,
                epochStale = false,
                freshness = Freshness.CachedOrFetch,
            ),
            servesResident = false,
        )
    }

    @Test
    fun staleIfError_usesCachedOrFetchPlanning() {
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = false,
                freshness = Freshness.StaleIfError,
            ),
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = true,
                freshness = Freshness.StaleIfError,
            ),
            servesResident = true,
        )
        assertFetch(
            plan(
                hasResidentValue = false,
                meta = null,
                epochStale = false,
                freshness = Freshness.StaleIfError,
            ),
            servesResident = false,
        )
    }

    @Test
    fun maxAge_enforcesBoundMetadataAndEpoch() {
        val freshness = Freshness.MaxAge(5.minutes)

        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 540_000L),
                epochStale = false,
                freshness = freshness,
                nowEpochMillis = 600_000L,
            ),
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = false,
                freshness = freshness,
                nowEpochMillis = 600_000L,
            ),
            servesResident = false,
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = Long.MIN_VALUE),
                epochStale = false,
                freshness = freshness,
                nowEpochMillis = Long.MAX_VALUE,
            ),
            servesResident = false,
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = null,
                epochStale = false,
                freshness = freshness,
            ),
            servesResident = false,
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 540_000L),
                epochStale = true,
                freshness = freshness,
                nowEpochMillis = 600_000L,
            ),
            servesResident = false,
        )
    }

    @Test
    fun maxAge_treatsNegativeClockDeltaAsFresh() {
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 601_000L),
                epochStale = false,
                freshness = Freshness.MaxAge(5.minutes),
                nowEpochMillis = 600_000L,
            ),
        )
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = Long.MAX_VALUE),
                epochStale = false,
                freshness = Freshness.MaxAge(Duration.ZERO),
                nowEpochMillis = Long.MIN_VALUE,
            ),
        )
    }

    private fun plan(
        hasResidentValue: Boolean,
        meta: EngineStoreMeta?,
        epochStale: Boolean,
        freshness: Freshness,
        nowEpochMillis: Long = 0L,
    ): FetchPlan =
        DefaultFreshnessValidator.plan(
            FreshnessContext(
                hasResidentValue = hasResidentValue,
                meta = meta,
                epochStale = epochStale,
                freshness = freshness,
                nowEpochMillis = nowEpochMillis,
            ),
        )

    private fun meta(writtenAtEpochMillis: Long): EngineStoreMeta =
        EngineStoreMeta(
            writtenAtEpochMillis = writtenAtEpochMillis,
            etag = null,
        )

    private fun assertFetch(
        plan: FetchPlan,
        servesResident: Boolean,
    ) {
        assertEquals(servesResident, assertIs<FetchPlan.Fetch>(plan).servesResidentWhileFetching)
    }
}
