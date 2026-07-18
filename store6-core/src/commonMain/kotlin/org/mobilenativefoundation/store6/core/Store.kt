package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.flow.Flow

/**
 * Provides asynchronous access to non-null values identified by [StoreKey] instances.
 *
 * Create stores through the [store] DSL. Implementing this interface directly is a delicate
 * operation: implementations must uphold every documented semantic, including the one-failure-
 * channel rule ([stream] emits and never throws; [get] throws and never emits) and the
 * completion postconditions of the maintenance operations.
 *
 * @param K the key type accepted by this store
 * @param V the non-null value type produced by this store
 */
@SubclassOptInRequired(DelicateStoreApi::class)
public interface Store<K : StoreKey, out V : Any> {
    /**
     * Observes retrieval state and values for [key].
     *
     * Fetch failures are emitted as [StoreResult.Error] values rather than thrown to the
     * collector; the flow stays live after a failure. The flow remains active until its
     * collector is cancelled or the store is closed, and continues to report later values,
     * including refetches triggered by invalidation and the absent-value transition after a
     * clear. Concurrent collectors and callers for one key share a single fetch.
     *
     * Present behavior: [freshness] is accepted but the engine honors the default
     * serve-resident-else-fetch posture for every policy until the freshness engine lands, as
     * documented on [Freshness].
     *
     * @param key the key to observe
     * @param freshness the freshness policy applied to this observation
     * @return a flow of loading, data, and error results for the key
     * @throws IllegalStateException if the store is closed before this call or before collection
     * begins
     */
    public fun stream(
        key: K,
        freshness: Freshness = Freshness.CachedOrFetch,
    ): Flow<StoreResult<V>>

    /**
     * Returns the value for [key], using a resident value when one is available.
     *
     * A stale resident value is served immediately and refreshed in the background. When no
     * resident value exists, the call joins the key's single in-flight fetch or starts one.
     *
     * Present behavior: [freshness] is accepted but the engine honors the default
     * serve-resident-else-fetch posture for every policy until the freshness engine lands.
     *
     * @param key the key whose value is requested
     * @param freshness the freshness policy applied to this read
     * @return the resolved value
     * @throws StoreException when no value can be returned: the fetch failed, or a concurrent
     * [clear] removed the key while its fetch was in flight ([StoreError.Missing])
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun get(
        key: K,
        freshness: Freshness = Freshness.CachedOrFetch,
    ): V

    /**
     * Marks the value for [key] stale without removing it.
     *
     * On return, active streams of [key] have been signaled and will observe refetched data;
     * the resident value is kept and served as stale in the meantime. Invalidation is
     * level-triggered monotone state, so a signal issued during any race window is never lost.
     *
     * Present behavior: the stale mark covers resident state; durable stale marks that survive
     * process restart land with the invalidation engine.
     *
     * @param key the key to invalidate
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun invalidate(key: K)

    /**
     * Marks every key in [namespace] stale without removing values.
     *
     * Present behavior: covers every key this store instance has seen — which is complete
     * coverage of resident state, the only state that exists at this stage. Durable namespace
     * watermarks covering evicted and never-seen keys across restarts land with the
     * invalidation engine.
     *
     * @param namespace the namespace to invalidate
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun invalidateNamespace(namespace: StoreNamespace)

    /**
     * Marks every key in this store stale without removing values.
     *
     * Present behavior: see [invalidateNamespace] for the coverage statement.
     *
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun invalidateAll()

    /**
     * Destructively removes the value for [key].
     *
     * On return, the resident value is gone: active streams observe the absent-value transition
     * ([StoreResult.Loading]) and then refetched data, and an in-flight fetch that started
     * before the clear can no longer commit — its waiters observe [StoreError.Missing].
     *
     * Present behavior: removal covers resident state; durable row deletion lands with the
     * source-of-truth engine.
     *
     * @param key the key to clear
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun clear(key: K)

    /**
     * Destructively removes every value in [namespace].
     *
     * Present behavior: see [invalidateNamespace] for the coverage statement and [clear] for
     * per-key semantics.
     *
     * @param namespace the namespace to clear
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun clearNamespace(namespace: StoreNamespace)

    /**
     * Destructively removes every value in this store.
     *
     * Present behavior: see [clearNamespace].
     *
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun clearAll()

    /**
     * Releases resources owned by this store and cancels its in-flight work.
     *
     * Collectors and value requests waiting on in-flight work are cancelled. Subsequent calls
     * to any operation fail with [IllegalStateException] and the message `Store is closed.`
     * Calling `close()` more than once has no additional effect.
     */
    public fun close()
}
