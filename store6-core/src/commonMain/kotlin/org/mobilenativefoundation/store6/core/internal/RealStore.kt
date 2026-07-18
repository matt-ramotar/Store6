package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult

/**
 * Store implementation backed by one supervised [KeyEngine] per canonical key.
 *
 * Each engine receives its own supervised child scope. Closing the store cancels the parent job
 * and all active engine work without allowing one key's fetch failure to cancel another key.
 * Freshness parameters are accepted at the surface; the engine currently honors the default
 * serve-resident-else-fetch posture for every policy (documented on [Freshness]).
 */
@OptIn(DelicateStoreApi::class)
internal class RealStore<K : StoreKey, V : Any>(
    fetcher: suspend (K) -> V,
) : Store<K, V> {
    private val storeJob = SupervisorJob()
    private val storeScope = CoroutineScope(Dispatchers.Default + storeJob)
    private val registry =
        KeyRegistry<K, V> { key ->
            val engineJob = SupervisorJob(storeJob)
            KeyEngine(
                key = key,
                fetcher = fetcher,
                engineScope = CoroutineScope(storeScope.coroutineContext + engineJob),
            )
        }

    override fun stream(
        key: K,
        freshness: Freshness,
    ): Flow<StoreResult<V>> {
        ensureOpen()
        return flow {
            ensureOpen()
            registry.withEngine(key) { engine ->
                emitAll(engine.stream())
            }
        }
    }

    override suspend fun get(
        key: K,
        freshness: Freshness,
    ): V {
        ensureOpen()
        return registry.withEngine(key) { engine -> engine.get() }
    }

    override suspend fun invalidate(key: K) {
        ensureOpen()
        registry.withEngine(key) { engine -> engine.invalidate() }
    }

    override suspend fun invalidateNamespace(namespace: StoreNamespace) {
        ensureOpen()
        registry.forEachResident(namespace.value) { engine -> engine.invalidate() }
    }

    override suspend fun invalidateAll() {
        ensureOpen()
        registry.forEachResident(namespace = null) { engine -> engine.invalidate() }
    }

    override suspend fun clear(key: K) {
        ensureOpen()
        registry.withEngine(key) { engine -> engine.clear() }
    }

    override suspend fun clearNamespace(namespace: StoreNamespace) {
        ensureOpen()
        registry.forEachResident(namespace.value) { engine -> engine.clear() }
    }

    override suspend fun clearAll() {
        ensureOpen()
        registry.forEachResident(namespace = null) { engine -> engine.clear() }
    }

    override fun close() {
        storeJob.cancel(CancellationException(STORE_CLOSED_MESSAGE))
    }

    /** Fails deterministically when an operation starts after [close]. */
    private fun ensureOpen() {
        if (!storeJob.isActive) {
            throw storeClosedException()
        }
    }
}
