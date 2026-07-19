package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.FakeWallClock
import org.mobilenativefoundation.store6.core.FetcherResult
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.TestKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookkeeperOrderingConformanceTest {
    private val key = TestKey("1")
    private val keyId = KeyId.from(key)

    @Test
    fun clearCannotBeOvertakenByAnOlderSuccessfulCommit() = runTest {
        val successGate = MutationGate()
        val bookkeeper = GateableBookkeeper(successGate = successGate)
        val engine = engine(bookkeeper) { FetcherResult.Success("v1", etag = "e1") }

        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        testScheduler.runCurrent()
        successGate.entered.await()

        val clear =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.clear()
            }
        testScheduler.runCurrent()

        successGate.release()
        testScheduler.runCurrent()
        read.await()
        clear.await()

        assertNull(bookkeeper.status(keyId))
    }

    @Test
    fun serverDeleteCannotEraseANewerSuccessfulCommit() = runTest {
        val forgetGate = MutationGate()
        val bookkeeper = GateableBookkeeper(forgetGate = forgetGate)
        var calls = 0
        val engine =
            engine(bookkeeper) {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> FetcherResult.Deleted
                    3 -> FetcherResult.Success("v2", etag = "e2")
                    else -> error("unexpected fetch call $calls")
                }
            }

        val seed =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.get(Freshness.CachedOrFetch)
            }
        testScheduler.runCurrent()
        assertEquals("v1", seed.await())

        val deletion =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.MustBeFresh) }
            }
        testScheduler.runCurrent()
        forgetGate.entered.await()

        val replacement =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.get(Freshness.CachedOrFetch)
            }
        testScheduler.runCurrent()

        forgetGate.release()
        testScheduler.runCurrent()
        deletion.await()
        assertEquals("v2", replacement.await())
        assertEquals("e2", bookkeeper.status(keyId)?.meta?.etag)
    }

    @Test
    fun failureAfterClearCannotRecreateFailureStatus() = runTest {
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val bookkeeper = GateableBookkeeper()
        val engine =
            engine(bookkeeper) {
                fetchStarted.complete(Unit)
                releaseFetch.await()
                throw IllegalStateException("boom")
            }

        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        testScheduler.runCurrent()
        fetchStarted.await()

        val clear =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.clear()
            }
        testScheduler.runCurrent()
        clear.await()

        releaseFetch.complete(Unit)
        testScheduler.runCurrent()
        read.await()

        assertNull(bookkeeper.status(keyId))
    }

    @Test
    fun failureTimestampIsCapturedBeforeOrderedBookkeepingWait() = runTest {
        val forgetGate = MutationGate()
        val bookkeeper = GateableBookkeeper(forgetGate = forgetGate)
        val clock = FakeWallClock(now = 0L)
        val engine =
            engine(bookkeeper, clock) {
                FetcherResult.Error(IllegalStateException("boom"))
            }

        val clear =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.clear()
            }
        testScheduler.runCurrent()
        forgetGate.entered.await()

        clock.now = 10L
        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        testScheduler.runCurrent()

        clock.now = 99L
        forgetGate.release()
        testScheduler.runCurrent()
        clear.await()
        read.await()

        assertEquals(10L, bookkeeper.status(keyId)?.lastFailureAtEpochMillis)
    }

    @Test
    fun engineCancellationReleasesBlockedFailureBookkeeping() = runTest {
        val failureGate = MutationGate()
        val bookkeeper = GateableBookkeeper(failureGate = failureGate)
        val engineJob = Job(backgroundScope.coroutineContext[Job])
        val engineScope = CoroutineScope(backgroundScope.coroutineContext + engineJob)
        val engine =
            engine(bookkeeper, engineScope = engineScope) {
                FetcherResult.Error(IllegalStateException("boom"))
            }
        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        testScheduler.runCurrent()
        failureGate.entered.await()

        try {
            engineJob.cancel()
            val clear =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.clear()
                }
            testScheduler.runCurrent()

            assertTrue(clear.isCompleted, "engine cancellation must release bookkeepingLock")
            clear.await()
        } finally {
            failureGate.release()
            testScheduler.runCurrent()
        }
        read.await()
    }

    private fun TestScope.engine(
        bookkeeper: Bookkeeper,
        clock: FakeWallClock = FakeWallClock(now = 0L),
        engineScope: CoroutineScope = backgroundScope,
        fetcher: suspend (TestKey) -> FetcherResult<String>,
    ): KeyEngine<TestKey, String> =
        KeyEngine(
            key = key,
            keyId = keyId,
            fetcher = fetcher,
            bookkeeper = bookkeeper,
            validator = DefaultFreshnessValidator,
            wallClock = clock,
            engineScope = engineScope,
        )

    private class MutationGate {
        val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        suspend fun pause() {
            entered.complete(Unit)
            released.await()
        }

        fun release() {
            released.complete(Unit)
        }
    }

    private class GateableBookkeeper(
        private val successGate: MutationGate? = null,
        private val failureGate: MutationGate? = null,
        private val forgetGate: MutationGate? = null,
    ) : Bookkeeper {
        private val delegate = InMemoryBookkeeper()

        override suspend fun recordSuccess(
            key: KeyId,
            meta: StoreMeta,
        ) {
            successGate?.pause()
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: KeyId,
            atEpochMillis: Long,
        ) {
            failureGate?.pause()
            delegate.recordFailure(key, atEpochMillis)
        }

        override suspend fun status(key: KeyId): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: KeyId) {
            forgetGate?.pause()
            delegate.forget(key)
        }
    }
}
