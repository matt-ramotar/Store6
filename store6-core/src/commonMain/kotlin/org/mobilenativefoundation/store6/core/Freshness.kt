package org.mobilenativefoundation.store6.core

import kotlin.time.Duration

/**
 * A per-call policy describing how fresh a value must be before it is served.
 *
 * Each read is planned from resident availability, invalidation state, typed metadata, and this
 * policy. Concurrent requests for one key still share a single in-flight fetch even when their
 * policies differ.
 */
public sealed interface Freshness {
    /**
     * The default policy: serve a cached value immediately when one exists and revalidate in the
     * background when it is stale; fetch when nothing is cached.
     */
    public data object CachedOrFetch : Freshness

    /**
     * Serve a cached value only when it has not been invalidated and its age does not exceed
     * [notOlderThan]; otherwise withhold it and fetch a fresh value.
     */
    public class MaxAge(
        /** The oldest a served value may be before a fetch is required. */
        public val notOlderThan: Duration,
    ) : Freshness

    /**
     * Never serve a cached value; block until a fresh fetch succeeds and fail when it does not.
     */
    public data object MustBeFresh : Freshness

    /**
     * Prefer fresh data after invalidation but fall back to the stale cached value when the fetch
     * fails. A fresh resident value is served without fetching.
     */
    public data object StaleIfError : Freshness

    /**
     * Never invoke the fetcher; serve only locally available data and report
     * [StoreError.Missing] when none exists.
     *
     * The builder still requires a fetcher; LocalOnly never invokes it. Fetcher-less stores
     * arrive with a later release (FR-10).
     */
    public data object LocalOnly : Freshness
}
