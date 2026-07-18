package org.mobilenativefoundation.store6.core

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
    private var fetcher: (suspend (K) -> V)? = null

    /**
     * Configures the suspending function used to retrieve a value for a key.
     *
     * Calling this function more than once replaces the previously configured fetcher.
     * A thrown fetcher exception is emitted as [StoreResult.Error] by [Store.stream] and
     * wrapped in [StoreException] by [Store.get].
     *
     * @param fetch the function that retrieves and returns a value for the supplied key
     */
    public fun fetcher(fetch: suspend (K) -> V) {
        this.fetcher = fetch
    }

    internal fun build(): Store<K, V> {
        val fetch = requireNotNull(fetcher) {
            "store<K, V> { } requires a fetcher { } block."
        }
        return TODO("Store engine is not implemented")
    }
}
