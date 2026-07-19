package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.internal.Bookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.RealStore
import org.mobilenativefoundation.store6.core.internal.SystemWallClock
import org.mobilenativefoundation.store6.core.internal.WallClock

/**
 * Creates a [Store] using the settings supplied by [configure].
 *
 * @param K the key type accepted by the store
 * @param V the non-null value type produced by the store
 * @param configure configuration applied before the store is created
 * @return the configured store
 * @throws IllegalArgumentException if no fetcher is configured
 */
public fun <K : StoreKey, V : Any> store(
    configure: StoreBuilder<K, V>.() -> Unit,
): Store<K, V> = StoreBuilder<K, V>().apply(configure).build()

/**
 * Configuration receiver for the [store] creation DSL.
 *
 * @param K the key type accepted by the store
 * @param V the non-null value type produced by the store
 */
public class StoreBuilder<K : StoreKey, V : Any> internal constructor() {
    private var fetcher: (suspend (K) -> FetcherResult<V>)? = null
    internal var wallClock: WallClock = SystemWallClock
    internal var bookkeeper: Bookkeeper = InMemoryBookkeeper()

    /**
     * Configures the suspending function used to retrieve a value for a key.
     *
     * This is success-or-throw sugar for [fetcherOfResult]: a returned value becomes
     * [FetcherResult.Success], while a thrown exception propagates through the store's fetch-failure
     * path. The last registration wins across calls to either function.
     *
     * @param fetch the function that retrieves and returns a value for the supplied key
     */
    public fun fetcher(fetch: suspend (K) -> V) {
        this.fetcher = { key -> FetcherResult.Success(fetch(key)) }
    }

    /**
     * Configures a fetcher that returns the full [FetcherResult] vocabulary.
     *
     * The name follows v5's `Fetcher.ofResult`. A second named function preserves the builder
     * strategy, adds no overload to an existing signature (avoiding a Swift overload explosion),
     * and leaves the quickstart unchanged. An overload of [fetcher] is intentionally rejected
     * because lambda-return-type inference would make calls ambiguous. The last registration wins
     * across calls to either [fetcher] or [fetcherOfResult].
     *
     * @param fetch the function that returns a rich result for the supplied key
     */
    public fun fetcherOfResult(fetch: suspend (K) -> FetcherResult<V>) {
        this.fetcher = fetch
    }

    internal fun build(): Store<K, V> {
        val fetch = requireNotNull(fetcher) {
            "store<K, V> { } requires a fetcher { } or fetcherOfResult { } block."
        }
        return RealStore(fetch, wallClock, bookkeeper)
    }
}
