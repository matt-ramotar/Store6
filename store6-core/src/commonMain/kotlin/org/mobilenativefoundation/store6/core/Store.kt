package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.flow.Flow

/**
 * Provides asynchronous access to non-null values identified by [StoreKey] instances.
 *
 * @param K the key type accepted by this store
 * @param V the non-null value type produced by this store
 */
public interface Store<K : StoreKey, out V : Any> {
    /**
     * Observes retrieval state and values for [key].
     *
     * Fetch failures are emitted as [StoreResult.Error] values rather than thrown to the
     * collector. The returned flow remains active so it can continue to report later values.
     *
     * @param key the key to observe
     * @return a flow of loading, data, and error results for the key
     */
    public fun stream(key: K): Flow<StoreResult<V>>

    /**
     * Returns the value for [key], using a resident value when one is available.
     *
     * @param key the key whose value is requested
     * @return the resolved value
     * @throws StoreException when a fetch failure prevents a value from being returned
     */
    public suspend fun get(key: K): V

    /**
     * Releases resources owned by this store and cancels its in-flight work.
     *
     * A closed store must not be used for further operations.
     */
    public fun close()
}
