package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.internal.Bookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

internal class RecordingBookkeeper(
    private val delegate: Bookkeeper = InMemoryBookkeeper(),
    private val events: MutableList<String> = mutableListOf(),
    var markStaleFailure: Throwable? = null,
    var advanceWatermarkFailure: Throwable? = null,
    var forgetFailure: Throwable? = null,
    var forgetNamespaceFailure: Throwable? = null,
    var forgetAllFailure: Throwable? = null,
) : Bookkeeper by delegate {
    val log: List<String> get() = events
    var markEntered: CompletableDeferred<Unit>? = null
    var releaseMark: CompletableDeferred<Unit>? = null
    var successEntered: CompletableDeferred<Unit>? = null
    var releaseSuccess: CompletableDeferred<Unit>? = null

    override suspend fun recordSuccess(
        key: KeyId,
        meta: StoreMeta,
    ) {
        successEntered?.complete(Unit)
        releaseSuccess?.await()
        events += "recordSuccess:${key.namespace}/${key.canonicalId}"
        delegate.recordSuccess(key, meta)
    }

    override suspend fun status(key: KeyId) =
        delegate.status(key).also {
            events += "status:${key.namespace}/${key.canonicalId}"
        }

    override suspend fun markStale(key: KeyId) {
        markEntered?.complete(Unit)
        releaseMark?.await()
        events += "markStale:${key.namespace}/${key.canonicalId}"
        markStaleFailure?.let { throw it }
        delegate.markStale(key)
    }

    override suspend fun advanceStaleWatermark(namespace: String) {
        events += "advanceStaleWatermark:$namespace"
        advanceWatermarkFailure?.let { throw it }
        delegate.advanceStaleWatermark(namespace)
    }

    override suspend fun advanceGlobalStaleWatermark() {
        events += "advanceGlobalStaleWatermark"
        advanceWatermarkFailure?.let { throw it }
        delegate.advanceGlobalStaleWatermark()
    }

    override suspend fun forget(key: KeyId) {
        events += "forget:${key.namespace}/${key.canonicalId}"
        forgetFailure?.let { throw it }
        delegate.forget(key)
    }

    override suspend fun forgetNamespace(namespace: String) {
        events += "forgetNamespace:$namespace"
        forgetNamespaceFailure?.let { throw it }
        delegate.forgetNamespace(namespace)
    }

    override suspend fun forgetAll() {
        events += "forgetAll"
        forgetAllFailure?.let { throw it }
        delegate.forgetAll()
    }
}

@OptIn(ExperimentalStoreApi::class)
internal open class RecordingSourceOfTruth<K : StoreKey, V : Any>(
    protected val delegate: SourceOfTruth<K, V>,
    private val events: MutableList<String> = mutableListOf(),
    var deleteFailure: Throwable? = null,
    var deleteNamespaceFailure: Throwable? = null,
    var deleteAllFailure: Throwable? = null,
) : SourceOfTruth<K, V> by delegate {
    val log: List<String> get() = events

    override suspend fun delete(key: K) {
        deleteFailure?.let { throw it }
        events += "delete:${key.namespace.value}/${key.canonicalId()}"
        delegate.delete(key)
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        deleteNamespaceFailure?.let { throw it }
        events += "deleteNamespace:${namespace.value}"
        delegate.deleteNamespace(namespace)
    }

    override suspend fun deleteAll() {
        deleteAllFailure?.let { throw it }
        events += "deleteAll"
        delegate.deleteAll()
    }
}

/** Holds a bulk delete after it is durable, including if the caller is cancelled. */
@OptIn(ExperimentalStoreApi::class)
internal class PostDeleteGateSourceOfTruth<K : StoreKey, V : Any>(
    delegate: SourceOfTruth<K, V>,
) : RecordingSourceOfTruth<K, V>(delegate) {
    val namespaceDeleted = CompletableDeferred<Unit>()
    val allDeleted = CompletableDeferred<Unit>()
    val releaseDelete = CompletableDeferred<Unit>()

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        super.deleteNamespace(namespace)
        withContext(NonCancellable) {
            namespaceDeleted.complete(Unit)
            releaseDelete.await()
        }
    }

    override suspend fun deleteAll() {
        super.deleteAll()
        withContext(NonCancellable) {
            allDeleted.complete(Unit)
            releaseDelete.await()
        }
    }
}
