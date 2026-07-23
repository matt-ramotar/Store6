@file:OptIn(
    DelicateStoreApi::class,
    ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.sqldelight

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
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.EmissionSequenceConformanceTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.FreshnessPolicyConformanceTest
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.StoreInvalidationConformanceTest
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

class StoreInvalidationConformanceAgainstHoppingSqlDelightSotTest :
    StoreInvalidationConformanceTest() {
    private lateinit var sourceOfTruth: PostCaptureReaderHopSourceOfTruth<*, *>

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        sourceOfTruth = builder.installHoppingSqlDelightSot()
    }

    override suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        sourceOfTruth.awaitCurrentReaderFirstDelivery(key)
    }
}

class EmissionSequenceConformanceAgainstHoppingSqlDelightSotTest :
    EmissionSequenceConformanceTest() {
    private lateinit var sourceOfTruth: PostCaptureReaderHopSourceOfTruth<*, *>

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        sourceOfTruth = builder.installHoppingSqlDelightSot()
    }

    override suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        sourceOfTruth.awaitCurrentReaderFirstDelivery(key)
    }
}

class FreshnessPolicyConformanceAgainstHoppingSqlDelightSotTest :
    FreshnessPolicyConformanceTest() {
    private lateinit var sourceOfTruth: PostCaptureReaderHopSourceOfTruth<*, *>

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        sourceOfTruth = builder.installHoppingSqlDelightSot()
    }

    override suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        sourceOfTruth.awaitCurrentReaderFirstDelivery(key)
    }
}

private fun <K : StoreKey, V : Any> StoreBuilder<K, V>.installHoppingSqlDelightSot():
    PostCaptureReaderHopSourceOfTruth<K, V> {
    val sourceOfTruth =
        PostCaptureReaderHopSourceOfTruth<K, V>(
            sqlDelightTestSot(
                harness = freshHarness(),
                readContext = Dispatchers.Default,
            ),
        )
    persistence(sourceOfTruth)
    return sourceOfTruth
}

private class PostCaptureReaderHopSourceOfTruth<K : StoreKey, V : Any>(
    private val delegate: SourceOfTruth<K, V>,
) : SourceOfTruth<K, V> {
    private val readerDeliveries = PostCaptureReaderFirstDeliveries()

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

    suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        val delivery = readerDeliveries.current(key)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { delivery.await() }
        }
    }
}

private class PostCaptureReaderFirstDeliveries {
    private val lock = Mutex()
    private val current = HashMap<PostCaptureReaderDeliveryKey, CompletableDeferred<Unit>>()

    suspend fun current(key: StoreKey): CompletableDeferred<Unit> =
        lock.withLock {
            current.getOrPut(PostCaptureReaderDeliveryKey.from(key)) { CompletableDeferred() }
        }

    suspend fun rotate(key: StoreKey) {
        lock.withLock {
            current[PostCaptureReaderDeliveryKey.from(key)] = CompletableDeferred()
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

private data class PostCaptureReaderDeliveryKey(
    val namespace: String,
    val canonicalId: String,
) {
    companion object {
        fun from(key: StoreKey): PostCaptureReaderDeliveryKey =
            PostCaptureReaderDeliveryKey(
                namespace = key.namespace.value,
                canonicalId = key.canonicalId(),
            )
    }
}
