package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalStoreApi::class)
class StoreBuilderPersistenceTest {
    @Test
    fun persistenceRegistration_keepsCurrentBuildBehaviorUnchanged() = runTest {
        val sourceOfTruth = InMemorySourceOfTruth<TestKey, String>()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "fetched" }
        }

        assertEquals("fetched", store.get(TestKey("key")))
        sourceOfTruth.reader(TestKey("key")).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }
}
