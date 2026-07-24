package org.mobilenativefoundation.store6.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest

class RoomKmpSpikeTest {
    @Test
    fun commonMainDaoIsGeneratedAndOperable() = runTest {
        val database = createTestDatabase()
        try {
            database.spikeDao().upsert(T1RoomSpikeEntity(id = 1, value = "stored"))

            assertEquals("stored", database.spikeDao().read(1)?.value)
        } finally {
            database.close()
        }
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
