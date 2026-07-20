package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.internal.SharedFlowSourceOfTruth

@OptIn(ExperimentalStoreApi::class)
class StoreConformanceAgainstSharedFlowSotTest : StoreConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(SharedFlowSourceOfTruth())
    }
}

@OptIn(ExperimentalStoreApi::class)
class StoreInvalidationConformanceAgainstSharedFlowSotTest : StoreInvalidationConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(SharedFlowSourceOfTruth())
    }
}

@OptIn(ExperimentalStoreApi::class)
class EmissionSequenceConformanceAgainstSharedFlowSotTest : EmissionSequenceConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(SharedFlowSourceOfTruth())
    }
}

@OptIn(ExperimentalStoreApi::class)
class SingleFlightConformanceAgainstSharedFlowSotTest : SingleFlightConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(SharedFlowSourceOfTruth())
    }
}

@OptIn(ExperimentalStoreApi::class)
class FreshnessPolicyConformanceAgainstSharedFlowSotTest : FreshnessPolicyConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(SharedFlowSourceOfTruth())
    }
}
