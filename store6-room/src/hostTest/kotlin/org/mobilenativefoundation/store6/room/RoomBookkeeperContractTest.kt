@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit
import kotlin.time.Duration.Companion.seconds

class RoomBookkeeperContractTest : BookkeeperContractKit() {
    override fun createBookkeeper(): Bookkeeper {
        val database = createTestDatabase()
        return RoomBookkeeper(database, database.store6BookkeeperDao())
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
