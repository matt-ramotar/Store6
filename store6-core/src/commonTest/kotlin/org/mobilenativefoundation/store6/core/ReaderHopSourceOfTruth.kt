@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/**
 * Adversarial test decorator that adds a real-dispatcher hop and yield to widen the
 * queued-reader-frame race.
 *
 * SQLDelight's `readContext` switches before row capture, so its adversarial lane uses an
 * equivalent post-capture decorator instead of treating `Default` versus `EmptyCoroutineContext`
 * as this seam. This decorator is test-only and unpublished; mutations are left untouched.
 */
internal class ReaderHopSourceOfTruth<K : StoreKey, V : Any>(
    private val delegate: SourceOfTruth<K, V>,
) : SourceOfTruth<K, V> {
    private val readerDeliveries = ReaderFirstDeliveries()

    override fun reader(key: K): Flow<V?> =
        flow {
            val delivery = readerDeliveries.current(key)
            delegate.reader(key)
                .map {
                    yield()
                    it
                }.flowOn(Dispatchers.Default)
                .collect { value ->
                    emit(value)
                    delivery.complete(Unit)
                }
        }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        delegate.write(key, value)
    }

    override suspend fun delete(key: K) {
        delegate.delete(key)
        readerDeliveries.rotate(key)
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        delegate.deleteNamespace(namespace)
        readerDeliveries.rotate(namespace)
    }

    override suspend fun deleteAll() {
        delegate.deleteAll()
        readerDeliveries.rotateAll()
    }

    internal suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        val delivery = readerDeliveries.current(key)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { delivery.await() }
        }
    }
}

private class ReaderFirstDeliveries {
    private val lock = Mutex()
    private val current = HashMap<ReaderDeliveryKey, CompletableDeferred<Unit>>()

    suspend fun current(key: StoreKey): CompletableDeferred<Unit> =
        lock.withLock {
            current.getOrPut(ReaderDeliveryKey.from(key)) { CompletableDeferred() }
        }

    suspend fun rotate(key: StoreKey) {
        lock.withLock {
            current[ReaderDeliveryKey.from(key)] = CompletableDeferred()
        }
    }

    suspend fun rotate(namespace: StoreNamespace) {
        lock.withLock {
            current.keys
                .filter { key -> key.namespace == namespace.value }
                .forEach { key -> current[key] = CompletableDeferred() }
        }
    }

    suspend fun rotateAll() {
        lock.withLock {
            current.keys.forEach { key -> current[key] = CompletableDeferred() }
        }
    }
}

private data class ReaderDeliveryKey(
    val namespace: String,
    val canonicalId: String,
) {
    companion object {
        fun from(key: StoreKey): ReaderDeliveryKey =
            ReaderDeliveryKey(
                namespace = key.namespace.value,
                canonicalId = key.canonicalId(),
            )
    }
}
