package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/** Reusable SharedFlow-backed source-of-truth fake exercised by the full contract kit. */
@OptIn(ExperimentalStoreApi::class)
internal class SharedFlowSourceOfTruth<K : StoreKey, V : Any> : SourceOfTruth<K, V> {
    private val lock = Mutex()
    private val slots = HashMap<KeyId, MutableSharedFlow<V?>>()
    private val pendingReaderObservation =
        MutableStateFlow<ReaderObservationWait<V>?>(null)

    override fun reader(key: K): Flow<V?> =
        flow {
            val keyId = KeyId.from(key)
            slotFor(key).collect { row ->
                emit(row)
                pendingReaderObservation.value?.let { wait ->
                    if (wait.keyId == keyId && wait.row == row && wait.observed.complete(Unit)) {
                        pendingReaderObservation.compareAndSet(wait, null)
                    }
                }
            }
        }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        update(key, value)
    }

    override suspend fun delete(key: K) {
        update(key, null)
    }

    /** Returns a causal test barrier completed after [row] crosses the reader's downstream emit. */
    fun expectReaderObservation(
        key: K,
        row: V?,
    ): CompletableDeferred<Unit> {
        val wait =
            ReaderObservationWait(
                keyId = KeyId.from(key),
                row = row,
                observed = CompletableDeferred(),
            )
        check(pendingReaderObservation.compareAndSet(null, wait)) {
            "A reader-observation expectation is already pending."
        }
        return wait.observed
    }

    private suspend fun update(
        key: K,
        row: V?,
    ) {
        val slot = slotFor(key)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            slot.emit(row)
        }
    }

    private suspend fun slotFor(key: K): MutableSharedFlow<V?> {
        val keyId = KeyId.from(key)
        return lock.withLock {
            slots.getOrPut(keyId) { newSlot() }
        }
    }

    private fun newSlot(): MutableSharedFlow<V?> =
        MutableSharedFlow<V?>(
            replay = 1,
            extraBufferCapacity = 64,
        ).also { slot ->
            check(slot.tryEmit(null))
        }

    private data class ReaderObservationWait<V : Any>(
        val keyId: KeyId,
        val row: V?,
        val observed: CompletableDeferred<Unit>,
    )
}
