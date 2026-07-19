package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class InMemoryBookkeeperTest {
    private val keyA = KeyId(namespace = "test", canonicalId = "a")
    private val keyB = KeyId(namespace = "test", canonicalId = "b")

    @Test
    fun successSequence_isMonotoneAcrossKeys() = runTest {
        val bookkeeper = InMemoryBookkeeper()

        bookkeeper.recordSuccess(keyA, EngineStoreMeta(writtenAtEpochMillis = 1L, etag = "a"))
        bookkeeper.recordSuccess(keyB, EngineStoreMeta(writtenAtEpochMillis = 2L, etag = "b"))

        val first = requireNotNull(bookkeeper.status(keyA))
        val second = requireNotNull(bookkeeper.status(keyB))
        assertEquals(1L, first.lastSuccessSequence)
        assertEquals(2L, second.lastSuccessSequence)
    }

    @Test
    fun failures_preserveSuccessAndSubsequentSuccessResetsFailures() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        val e1 = EngineStoreMeta(writtenAtEpochMillis = 1L, etag = "e1")
        bookkeeper.recordSuccess(keyA, e1)

        bookkeeper.recordFailure(keyA, atEpochMillis = 2L)
        bookkeeper.recordFailure(keyA, atEpochMillis = 3L)

        val failed = requireNotNull(bookkeeper.status(keyA))
        assertSame(e1, failed.meta)
        assertEquals(1L, failed.lastSuccessSequence)
        assertEquals(3L, failed.lastFailureAtEpochMillis)
        assertEquals(2, failed.consecutiveFailures)

        val e2 = EngineStoreMeta(writtenAtEpochMillis = 4L, etag = "e2")
        bookkeeper.recordSuccess(keyA, e2)

        val recovered = requireNotNull(bookkeeper.status(keyA))
        assertSame(e2, recovered.meta)
        assertEquals(2L, recovered.lastSuccessSequence)
        assertNull(recovered.lastFailureAtEpochMillis)
        assertEquals(0, recovered.consecutiveFailures)
    }

    @Test
    fun forget_dropsRecord() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        bookkeeper.recordSuccess(keyA, EngineStoreMeta(writtenAtEpochMillis = 1L, etag = null))

        bookkeeper.forget(keyA)

        assertNull(bookkeeper.status(keyA))
    }
}
