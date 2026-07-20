package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.contracttests.SourceOfTruthContractKit
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.NamespacedTestKey
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

@OptIn(ExperimentalStoreApi::class)
class InMemorySourceOfTruthKitTest : SourceOfTruthContractKit<NamespacedTestKey, String>() {
    override fun createSourceOfTruth(): SourceOfTruth<NamespacedTestKey, String> =
        InMemorySourceOfTruth()

    override val keyA: NamespacedTestKey = NamespacedTestKey(ns = "primary", id = "a")
    override val keyB: NamespacedTestKey = NamespacedTestKey(ns = "primary", id = "b")
    override val keyOtherNamespace: NamespacedTestKey =
        NamespacedTestKey(ns = "other", id = "a")

    override fun value(index: Int): String = "value-$index"
}

@OptIn(ExperimentalStoreApi::class)
class SharedFlowSourceOfTruthKitTest : SourceOfTruthContractKit<NamespacedTestKey, String>() {
    override fun createSourceOfTruth(): SourceOfTruth<NamespacedTestKey, String> =
        SharedFlowSourceOfTruth()

    override val keyA: NamespacedTestKey = NamespacedTestKey(ns = "primary", id = "a")
    override val keyB: NamespacedTestKey = NamespacedTestKey(ns = "primary", id = "b")
    override val keyOtherNamespace: NamespacedTestKey =
        NamespacedTestKey(ns = "other", id = "a")

    override fun value(index: Int): String = "value-$index"
}
