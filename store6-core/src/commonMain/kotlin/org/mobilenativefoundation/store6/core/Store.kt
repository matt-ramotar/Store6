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
     * collector. The returned flow remains active so it can continue to report later values,
     * until its collector is cancelled or the store is closed.
     *
     * @param key the key to observe
     * @return a flow of loading, data, and error results for the key
     * @throws IllegalStateException if the store is closed before this call or before collection
     * begins
     */
    public fun stream(key: K): Flow<StoreResult<V>>

    /**
     * Returns the value for [key], using a resident value when one is available.
     *
     * @param key the key whose value is requested
     * @return the resolved value
     * @throws StoreException when a fetch failure prevents a value from being returned
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun get(key: K): V

    /**
     * Releases resources owned by this store and cancels its in-flight work.
     *
     * Collectors and value requests waiting on in-flight work are cancelled. Subsequent
     * [stream] and [get] calls fail with [IllegalStateException] and the message
     * `Store is closed.` Calling `close()` more than once has no additional effect.
     */
    public fun close()
}
