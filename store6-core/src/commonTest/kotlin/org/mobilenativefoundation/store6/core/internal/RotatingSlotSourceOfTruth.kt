package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/**
 * Fault fixture that intentionally violates the source-of-truth reader-liveness contract.
 *
 * A delete rotates to a new null-seeded slot without notifying collectors of the old slot. This is
 * reserved for later engine recovery tests and must never receive a `SourceOfTruthContractKit`
 * runner.
 */
@OptIn(ExperimentalStoreApi::class)
internal class RotatingSlotSourceOfTruth<K : StoreKey, V : Any> : SourceOfTruth<K, V> {
    private class Entry<V : Any>(
        var slot: MutableSharedFlow<V?>,
        var subscriptionCount: Int,
    )

    private val lock = Mutex()
    private val entries = HashMap<KeyId, Entry<V>>()

    override fun reader(key: K): Flow<V?> =
        flow {
            emitAll(captureSlot(key))
        }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        val keyId = KeyId.from(key)
        lock.withLock {
            check(entryFor(keyId).slot.tryEmit(value))
        }
    }

    override suspend fun delete(key: K) {
        val keyId = KeyId.from(key)
        lock.withLock {
            entryFor(keyId).slot = newSlot()
        }
    }

    internal suspend fun subscriptionCount(key: K): Int {
        val keyId = KeyId.from(key)
        return lock.withLock { entries[keyId]?.subscriptionCount ?: 0 }
    }

    private suspend fun captureSlot(key: K): MutableSharedFlow<V?> {
        val keyId = KeyId.from(key)
        return lock.withLock {
            entryFor(keyId).also { entry ->
                entry.subscriptionCount += 1
            }.slot
        }
    }

    private fun entryFor(keyId: KeyId): Entry<V> =
        entries.getOrPut(keyId) {
            Entry(slot = newSlot(), subscriptionCount = 0)
        }

    private fun newSlot(): MutableSharedFlow<V?> =
        MutableSharedFlow<V?>(
            replay = 1,
            extraBufferCapacity = 64,
        ).also { slot ->
            check(slot.tryEmit(null))
        }
}
