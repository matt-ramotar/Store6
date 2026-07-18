package org.mobilenativefoundation.store6.core

import kotlin.time.Duration

/**
 * A per-call policy describing how fresh a value must be before it is served.
 *
 * Present behavior: the engine currently honors one posture for every policy — serve the
 * resident value when one exists (refreshing it in the background when it is stale), otherwise
 * fetch. Policy-specific behavior (bounded age, blocking freshness, stale-if-error fallback,
 * local-only reads) lands with the freshness engine; the parameter is part of the frozen
 * signature so the surface does not change when it does.
 */
public sealed interface Freshness {
    /**
     * The default policy: serve a cached value immediately when one exists and revalidate in the
     * background when it is stale; fetch when nothing is cached.
     */
    public data object CachedOrFetch : Freshness

    /**
     * Serve a cached value only when its age does not exceed [notOlderThan]; otherwise fetch.
     *
     * Present behavior: treated as [CachedOrFetch] until the freshness engine lands.
     */
    public class MaxAge(
        /** The oldest a served value may be before a fetch is required. */
        public val notOlderThan: Duration,
    ) : Freshness

    /**
     * Never serve a cached value; block until a fresh fetch succeeds and fail when it does not.
     *
     * Present behavior: treated as [CachedOrFetch] until the freshness engine lands.
     */
    public data object MustBeFresh : Freshness

    /**
     * Prefer fresh data but fall back to a stale cached value when the fetch fails.
     *
     * Present behavior: treated as [CachedOrFetch] until the freshness engine lands.
     */
    public data object StaleIfError : Freshness

    /**
     * Never invoke the fetcher; serve only locally available data and report
     * [StoreError.Missing] when none exists.
     *
     * Present behavior: treated as [CachedOrFetch] — a fetch still occurs when no resident
     * value exists — until the freshness engine lands.
     */
    public data object LocalOnly : Freshness
}
