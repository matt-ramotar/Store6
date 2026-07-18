package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult

/**
 * Store implementation backed by one supervised [KeyEngine] per canonical key.
 *
 * Each engine receives its own supervised child scope. Closing the store cancels the parent job
 * and all active engine work without allowing one key's fetch failure to cancel another key.
 */
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

    override fun stream(key: K): Flow<StoreResult<V>> {
        ensureOpen()
        return flow {
            ensureOpen()
            registry.withEngine(key) { engine ->
                emitAll(engine.stream())
            }
        }
    }

    override suspend fun get(key: K): V {
        ensureOpen()
        return registry.withEngine(key) { engine -> engine.get() }
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
