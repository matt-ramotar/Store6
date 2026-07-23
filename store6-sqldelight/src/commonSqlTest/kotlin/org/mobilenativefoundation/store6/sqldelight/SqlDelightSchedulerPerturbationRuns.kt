@file:OptIn(
    DelicateStoreApi::class,
    ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.sqldelight

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installHoppingSqlDelightSot()
    }
}

class EmissionSequenceConformanceAgainstHoppingSqlDelightSotTest :
    EmissionSequenceConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installHoppingSqlDelightSot()
    }
}

class FreshnessPolicyConformanceAgainstHoppingSqlDelightSotTest :
    FreshnessPolicyConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installHoppingSqlDelightSot()
    }
}

private fun <K : StoreKey, V : Any> StoreBuilder<K, V>.installHoppingSqlDelightSot() {
    persistence(
        PostCaptureReaderHopSourceOfTruth(
            sqlDelightTestSot(
                harness = freshHarness(),
                readContext = Dispatchers.Default,
            ),
        ),
    )
}

private class PostCaptureReaderHopSourceOfTruth<K : StoreKey, V : Any>(
    private val delegate: SourceOfTruth<K, V>,
) : SourceOfTruth<K, V> {
    override fun reader(key: K): Flow<V?> =
        delegate.reader(key).map {
            yield()
            it
        }.flowOn(Dispatchers.Default)

    override suspend fun write(
        key: K,
        value: V,
    ) {
        delegate.write(key, value)
    }

    override suspend fun delete(key: K) {
        delegate.delete(key)
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        delegate.deleteNamespace(namespace)
    }

    override suspend fun deleteAll() {
        delegate.deleteAll()
    }
}
