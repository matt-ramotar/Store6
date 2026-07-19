package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FetcherContractTest {
    @Test
    fun fetcherResultError_cancellationIsEquivalentToThrowingCancellation() = runTest {
        val returnedCancellation = CancellationException("fetch cancelled")
        val richStore = store<TestKey, String> {
            fetcherOfResult { FetcherResult.Error(returnedCancellation) }
        }
        val thrownCancellation = CancellationException("fetch cancelled")
        val plainStore = store<TestKey, String> {
            fetcher { throw thrownCancellation }
        }

        try {
            val returnedFailure =
                assertFailsWith<CancellationException> {
                    richStore.get(TestKey("1"))
                }
            assertEquals("fetch cancelled", returnedFailure.message)
            assertTrue(
                returnedFailure === returnedCancellation ||
                    returnedFailure.cause === returnedCancellation,
            )

            val thrownFailure =
                assertFailsWith<CancellationException> {
                    plainStore.get(TestKey("1"))
                }
            assertEquals("fetch cancelled", thrownFailure.message)
            assertTrue(
                thrownFailure === thrownCancellation || thrownFailure.cause === thrownCancellation,
            )
        } finally {
            richStore.close()
            plainStore.close()
        }
    }
}
