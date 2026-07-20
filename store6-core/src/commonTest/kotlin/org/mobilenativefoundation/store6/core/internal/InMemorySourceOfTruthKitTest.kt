package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.contracttests.SourceOfTruthContractKit
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.NamespacedTestKey
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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

@OptIn(ExperimentalStoreApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedFlowSourceOfTruthLinearizationTest {
    @Test
    fun namespaceDeleteSerializesWriteAfterBulkReturn(): TestResult = runTest {
        val keyA = NamespacedTestKey(ns = "primary", id = "a")
        val keyB = NamespacedTestKey(ns = "primary", id = "b")
        val keyCreatedDuringBulk = NamespacedTestKey(ns = "primary", id = "new")
        val firstMatchingSlotDeleted = CompletableDeferred<Unit>()
        val releaseBulkDelete = CompletableDeferred<Unit>()
        val sourceOfTruth =
            SharedFlowSourceOfTruth<NamespacedTestKey, String> { keyId ->
                if (keyId == KeyId.from(keyA)) {
                    firstMatchingSlotDeleted.complete(Unit)
                    releaseBulkDelete.await()
                }
            }
        sourceOfTruth.write(keyA, "a")
        sourceOfTruth.write(keyB, "b")

        val bulkDelete = async { sourceOfTruth.deleteNamespace(keyA.namespace) }
        firstMatchingSlotDeleted.await()
        val laterWrite = async { sourceOfTruth.write(keyA, "later") }
        runCurrent()
        val writeInterleaved = laterWrite.isCompleted
        assertNull(sourceOfTruth.reader(keyCreatedDuringBulk).first())

        releaseBulkDelete.complete(Unit)
        bulkDelete.await()
        laterWrite.await()

        assertFalse(writeInterleaved)
        assertEquals("later", sourceOfTruth.reader(keyA).first())
    }
}
