package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.contracttests.SourceOfTruthContractKit
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.TestKey
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

@OptIn(ExperimentalStoreApi::class)
class InMemorySourceOfTruthKitTest : SourceOfTruthContractKit<TestKey, String>() {
    override fun createSourceOfTruth(): SourceOfTruth<TestKey, String> =
        InMemorySourceOfTruth()

    override val keyA: TestKey = TestKey("a")
    override val keyB: TestKey = TestKey("b")

    override fun value(index: Int): String = "value-$index"
}

@OptIn(ExperimentalStoreApi::class)
class SharedFlowSourceOfTruthKitTest : SourceOfTruthContractKit<TestKey, String>() {
    override fun createSourceOfTruth(): SourceOfTruth<TestKey, String> =
        SharedFlowSourceOfTruth()

    override val keyA: TestKey = TestKey("a")
    override val keyB: TestKey = TestKey("b")

    override fun value(index: Int): String = "value-$index"
}
