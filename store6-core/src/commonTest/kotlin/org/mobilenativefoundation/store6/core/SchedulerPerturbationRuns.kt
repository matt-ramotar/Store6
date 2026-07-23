@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth

class StoreInvalidationConformanceUnderReaderHopTest : StoreInvalidationConformanceTest() {
    private lateinit var sourceOfTruth: ReaderHopSourceOfTruth<*, *>

    override val requiresInitialReaderDeliveryFence: Boolean = true

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        val hoppingSourceOfTruth = ReaderHopSourceOfTruth<K, V>(InMemorySourceOfTruth())
        sourceOfTruth = hoppingSourceOfTruth
        builder.persistence(hoppingSourceOfTruth)
    }

    override suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        sourceOfTruth.awaitCurrentReaderFirstDelivery(key)
    }
}

class EmissionSequenceConformanceUnderReaderHopTest : EmissionSequenceConformanceTest() {
    private lateinit var sourceOfTruth: ReaderHopSourceOfTruth<*, *>

    override val requiresInitialReaderDeliveryFence: Boolean = true

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        val hoppingSourceOfTruth = ReaderHopSourceOfTruth<K, V>(InMemorySourceOfTruth())
        sourceOfTruth = hoppingSourceOfTruth
        builder.persistence(hoppingSourceOfTruth)
    }

    override suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        sourceOfTruth.awaitCurrentReaderFirstDelivery(key)
    }
}

class FreshnessPolicyConformanceUnderReaderHopTest : FreshnessPolicyConformanceTest() {
    private lateinit var sourceOfTruth: ReaderHopSourceOfTruth<*, *>

    override val requiresInitialReaderDeliveryFence: Boolean = true

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        val hoppingSourceOfTruth = ReaderHopSourceOfTruth<K, V>(InMemorySourceOfTruth())
        sourceOfTruth = hoppingSourceOfTruth
        builder.persistence(hoppingSourceOfTruth)
    }

    override suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        sourceOfTruth.awaitCurrentReaderFirstDelivery(key)
    }
}
