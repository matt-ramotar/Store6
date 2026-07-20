package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InMemoryBookkeeperTest {
    private val keyA = KeyId(namespace = "test", canonicalId = "a")
    private val keyB = KeyId(namespace = "test", canonicalId = "b")
    private val keyOtherNamespace = KeyId(namespace = "other", canonicalId = "a")

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

    @Test
    fun markStale_isScopedToOneKey() = runTest {
        val bookkeeper = InMemoryBookkeeper()

        assertNull(bookkeeper.status(keyA))
        bookkeeper.markStale(keyA)

        val marked = requireNotNull(bookkeeper.status(keyA))
        assertNull(marked.meta)
        assertNull(marked.lastSuccessSequence)
        assertTrue(marked.durablyStale)
        assertNull(bookkeeper.status(keyB))
    }

    @Test
    fun namespaceAndGlobalWatermarks_coverNeverSeenKeys() = runTest {
        val bookkeeper = InMemoryBookkeeper()

        assertNull(bookkeeper.status(keyA))
        assertNull(bookkeeper.status(keyOtherNamespace))

        bookkeeper.advanceStaleWatermark(keyA.namespace)

        assertTrue(requireNotNull(bookkeeper.status(keyA)).durablyStale)
        assertTrue(requireNotNull(bookkeeper.status(keyB)).durablyStale)
        assertNull(bookkeeper.status(keyOtherNamespace))

        bookkeeper.advanceGlobalStaleWatermark()

        assertTrue(requireNotNull(bookkeeper.status(keyOtherNamespace)).durablyStale)
    }

    @Test
    fun failureOnlyRecord_usesZeroSuccessFloor() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        bookkeeper.recordFailure(keyA, atEpochMillis = 10L)

        val failed = requireNotNull(bookkeeper.status(keyA))
        assertNull(failed.lastSuccessSequence)
        assertFalse(failed.durablyStale)

        bookkeeper.markStale(keyA)

        val marked = requireNotNull(bookkeeper.status(keyA))
        assertNull(marked.lastSuccessSequence)
        assertTrue(marked.durablyStale)
    }

    @Test
    fun laterSuccess_outranksKeyNamespaceAndGlobalMarks() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        bookkeeper.recordFailure(keyA, atEpochMillis = 10L)
        bookkeeper.markStale(keyA)
        bookkeeper.advanceStaleWatermark(keyA.namespace)
        bookkeeper.advanceGlobalStaleWatermark()
        val meta = EngineStoreMeta(writtenAtEpochMillis = 20L, etag = "fresh")

        bookkeeper.recordSuccess(keyA, meta)

        val status = requireNotNull(bookkeeper.status(keyA))
        assertSame(meta, status.meta)
        assertEquals(4L, status.lastSuccessSequence)
        assertNull(status.lastFailureAtEpochMillis)
        assertEquals(0, status.consecutiveFailures)
        assertFalse(status.durablyStale)
    }

    @Test
    fun forgetNamespace_dropsMatchingRecordsButPreservesWatermark() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        val matchingMeta = EngineStoreMeta(writtenAtEpochMillis = 1L, etag = "matching")
        val otherMeta = EngineStoreMeta(writtenAtEpochMillis = 2L, etag = "other")
        bookkeeper.recordSuccess(keyA, matchingMeta)
        bookkeeper.recordSuccess(keyOtherNamespace, otherMeta)
        bookkeeper.advanceStaleWatermark(keyA.namespace)

        bookkeeper.forgetNamespace(keyA.namespace)

        val forgotten = requireNotNull(bookkeeper.status(keyA))
        assertNull(forgotten.meta)
        assertNull(forgotten.lastSuccessSequence)
        assertTrue(forgotten.durablyStale)
        assertTrue(requireNotNull(bookkeeper.status(keyB)).durablyStale)
        assertSame(otherMeta, requireNotNull(bookkeeper.status(keyOtherNamespace)).meta)

        bookkeeper.recordSuccess(keyA, matchingMeta)

        val restored = requireNotNull(bookkeeper.status(keyA))
        assertEquals(4L, restored.lastSuccessSequence)
        assertFalse(restored.durablyStale)
    }

    @Test
    fun forgetAll_dropsRecordsButPreservesAllWatermarks() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        val matchingMeta = EngineStoreMeta(writtenAtEpochMillis = 1L, etag = "matching")
        val otherMeta = EngineStoreMeta(writtenAtEpochMillis = 2L, etag = "other")
        val neverSeen = KeyId(namespace = "never-seen", canonicalId = "key")
        bookkeeper.recordSuccess(keyA, matchingMeta)
        bookkeeper.recordSuccess(keyOtherNamespace, otherMeta)
        bookkeeper.advanceStaleWatermark(keyA.namespace)
        bookkeeper.advanceGlobalStaleWatermark()

        bookkeeper.forgetAll()

        val matching = requireNotNull(bookkeeper.status(keyA))
        val other = requireNotNull(bookkeeper.status(keyOtherNamespace))
        assertNull(matching.meta)
        assertNull(other.meta)
        assertTrue(matching.durablyStale)
        assertTrue(other.durablyStale)
        assertTrue(requireNotNull(bookkeeper.status(neverSeen)).durablyStale)

        bookkeeper.recordSuccess(keyOtherNamespace, otherMeta)

        val restored = requireNotNull(bookkeeper.status(keyOtherNamespace))
        assertEquals(5L, restored.lastSuccessSequence)
        assertFalse(restored.durablyStale)
    }
}
