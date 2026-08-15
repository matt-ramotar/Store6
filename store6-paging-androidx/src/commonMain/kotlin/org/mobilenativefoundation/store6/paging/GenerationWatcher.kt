package org.mobilenativefoundation.store6.paging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult

/** Owns the live Store collectors associated with one paging-source generation. */
internal class GenerationWatcher<K : StoreKey, V : Any>(
    private val store: Store<K, V>,
    private val invalidateGeneration: () -> Unit,
) {
    internal inner class BaselineFrame internal constructor(
        val result: StoreResult<V>,
        internal val entry: Entry<V>,
    )

    private data class KeyId(
        val namespace: String,
        val canonicalId: String,
    )

    internal enum class Resolution {
        ARMED,
        DISCARDED,
        STOPPED,
    }

    internal class Entry<V : Any> {
        val baseline = CompletableDeferred<StoreResult<V>?>()
        val armed = CompletableDeferred<Unit>()
        val resolution = CompletableDeferred<Resolution>()
        var job: Job? = null
    }

    private val generationJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + generationJob)
    private val mutex = Mutex()
    private val entries = mutableMapOf<KeyId, Entry<V>>()
    private var invalidationRequested = false

    suspend fun baseline(
        key: K,
        freshness: Freshness,
    ): BaselineFrame? {
        val id = KeyId(key.namespace.value, key.canonicalId())
        while (generationJob.isActive) {
            val (entry, ownsEntry) =
                mutex.withLock {
                    val existing = entries[id]
                    if (existing != null) {
                        existing to false
                    } else {
                        Entry<V>().also { entries[id] = it } to true
                    }
                }

            if (!ownsEntry) {
                when (entry.resolution.await()) {
                    Resolution.ARMED -> {
                        requestInvalidation()
                        return null
                    }
                    Resolution.DISCARDED -> continue
                    Resolution.STOPPED -> {
                        remove(id, entry)
                        continue
                    }
                }
            }

            entry.job =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var baselineObserved = false
                    try {
                        store.stream(key, freshness).collect { result ->
                            if (!baselineObserved) {
                                if (result is StoreResult.Loading) return@collect
                                baselineObserved = true
                                entry.baseline.complete(result)
                                entry.armed.await()
                            } else {
                                when (result) {
                                    is StoreResult.Data,
                                    is StoreResult.Loading,
                                    -> requestInvalidation()
                                    is StoreResult.Error,
                                    is StoreResult.Revalidated,
                                    -> Unit
                                }
                            }
                        }
                    } finally {
                        if (!entry.baseline.isCompleted) entry.baseline.complete(null)
                        if (!entry.resolution.isCompleted) {
                            entry.resolution.complete(Resolution.STOPPED)
                        }
                    }
                }

            val result = entry.baseline.await()
            if (result != null) return BaselineFrame(result, entry)
            remove(id, entry)
        }
        return null
    }

    suspend fun watch(
        key: K,
        baselineFrameObserved: BaselineFrame,
    ) {
        val entry = baselineFrameObserved.entry
        val id = KeyId(key.namespace.value, key.canonicalId())
        val arm =
            mutex.withLock {
                val ownsEntry = entries[id] === entry
                if (ownsEntry && generationJob.isActive && !invalidationRequested) {
                    entry.resolution.complete(Resolution.ARMED)
                    true
                } else {
                    if (ownsEntry) entries.remove(id)
                    if (!entry.resolution.isCompleted) {
                        entry.resolution.complete(Resolution.STOPPED)
                    }
                    false
                }
            }
        if (arm) {
            entry.armed.complete(Unit)
        } else {
            entry.job?.cancel()
        }
    }

    suspend fun discard(
        key: K,
        baselineFrameObserved: BaselineFrame,
    ) {
        val entry = baselineFrameObserved.entry
        val id = KeyId(key.namespace.value, key.canonicalId())
        mutex.withLock {
            if (entries[id] === entry) entries.remove(id)
            if (!entry.resolution.isCompleted) {
                entry.resolution.complete(Resolution.DISCARDED)
            }
        }
        entry.job?.cancel()
    }

    fun cancel() {
        generationJob.cancel()
    }

    private suspend fun requestInvalidation() {
        val shouldInvalidate =
            mutex.withLock {
                if (invalidationRequested || !generationJob.isActive) {
                    false
                } else {
                    invalidationRequested = true
                    true
                }
            }
        // Paging invalidation cancels this scope synchronously through its registered callback.
        // Invoke it only after releasing the mutex so callback cleanup cannot reenter the lock.
        if (shouldInvalidate) invalidateGeneration()
    }

    private suspend fun remove(
        id: KeyId,
        entry: Entry<V>,
    ) {
        mutex.withLock {
            if (entries[id] === entry) entries.remove(id)
        }
    }
}
