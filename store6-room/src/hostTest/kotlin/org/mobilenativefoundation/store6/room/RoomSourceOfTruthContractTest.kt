@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.testing.SourceOfTruthContractKit
import kotlin.time.Duration.Companion.seconds

internal class RoomSourceOfTruthContractTest :
    SourceOfTruthContractKit<RoomKitKey, String>() {
    override fun createSourceOfTruth(): SourceOfTruth<RoomKitKey, String> {
        val database = createTestDatabase()
        val dao = database.kitRowDao()
        return RoomSourceOfTruth(
            database = database,
            rowReader = { key ->
                dao.row(key.namespace.value, key.canonicalId()).map { it?.payload }
            },
            rowWriter = { key, value ->
                dao.upsert(KitRowEntity(key.namespace.value, key.canonicalId(), value))
            },
            rowDeleter = { key ->
                dao.delete(key.namespace.value, key.canonicalId())
            },
            namespaceDeleter = { namespace ->
                dao.deleteNamespace(namespace.value)
            },
            allDeleter = {
                dao.deleteAll()
            },
        )
    }

    override val keyA: RoomKitKey = RoomKitKey(StoreNamespace("users"), "a")
    override val keyB: RoomKitKey = RoomKitKey(StoreNamespace("users"), "b")
    override val keyOtherNamespace: RoomKitKey = RoomKitKey(StoreNamespace("teams"), "a")

    override fun value(index: Int): String = "value-$index"
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
