package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class KeyEngineSourceOfTruthRaceTest {
    @Test
    fun nullMetaHydration_servesLocalOnlyAndStartsOneCachedOrFetchRevalidation() = runTest {
        val sourceOfTruth = InMemorySourceOfTruth<TestKey, String>()
        val key = TestKey("key")
        sourceOfTruth.write(key, "durable")
        val fetched = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                fetchCalls += 1
                fetched.complete(Unit)
                "fresh"
            }
        }

        try {
            assertEquals("durable", store.get(key, Freshness.LocalOnly))
            assertEquals("durable", store.get(key, Freshness.CachedOrFetch))
            fetched.await()
            sourceOfTruth.reader(key).filter { it == "fresh" }.first()
            runCurrent()
            assertEquals(1, fetchCalls)
            assertEquals("fresh", store.get(key, Freshness.LocalOnly))
        } finally {
            store.close()
        }
    }

    @Test
    fun readerFactoryFailure_isTypedOnceForAnOutageAndRecovers() = runTest {
        val sourceOfTruth = FailingReaderSourceOfTruth()
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "seed" }
        }

        try {
            assertEquals("seed", store.get(key))
            sourceOfTruth.beginFactoryOutage(failures = 3)

            store.stream(key, Freshness.LocalOnly).test {
                assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                val failure = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Persistence>(failure.error)

                withContext(Dispatchers.Default) {
                    withTimeout(2_000L) { sourceOfTruth.recovered.await() }
                }
                assertTrue(sourceOfTruth.readerCalls >= 4)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun readerCollectionFailure_isTypedOnceForAContiguousOutageAndRecovers() = runTest {
        val sourceOfTruth = FailingReaderSourceOfTruth()
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "seed" }
        }

        try {
            assertEquals("seed", store.get(key))
            sourceOfTruth.beginCollectionOutage(failures = 3, emitBeforeFirstFailure = true)

            store.stream(key, Freshness.LocalOnly).test {
                assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                val failure = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Persistence>(failure.error)

                withContext(Dispatchers.Default) {
                    withTimeout(2_000L) { sourceOfTruth.recovered.await() }
                }
                assertTrue(sourceOfTruth.readerCalls >= 4)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun twoCollectorsShareOneReaderRetryLoop() = runTest {
        val sourceOfTruth = FailingReaderSourceOfTruth()
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "seed" }
        }

        try {
            assertEquals("seed", store.get(key))
            sourceOfTruth.beginFactoryOutage(failures = 1)

            app.cash.turbine.turbineScope {
                val first = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                val second = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(first.awaitItem()).value)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(second.awaitItem()).value)
                assertIs<StoreError.Persistence>(
                    assertIs<StoreResult.Error>(first.awaitItem()).error,
                )
                assertIs<StoreError.Persistence>(
                    assertIs<StoreResult.Error>(second.awaitItem()).error,
                )
                withContext(Dispatchers.Default) {
                    withTimeout(2_000L) { sourceOfTruth.recovered.await() }
                }
                assertEquals(2, sourceOfTruth.readerCalls)
                first.cancelAndIgnoreRemainingEvents()
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun hydrationReaderFailure_isTypedPersistenceForGetAndRecoversOnNextRead() = runTest {
        val boom = IllegalStateException("reader failed")
        val sourceOfTruth = AdapterFailureSourceOfTruth(readerFailure = boom)
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "unused" }
        }

        try {
            val failure =
                assertFailsWith<StoreException> {
                    store.get(TestKey("key"), Freshness.LocalOnly)
                }
            val persistence = assertIs<StoreError.Persistence>(failure.error)
            assertTrue(persistence.cause === boom)
            assertTrue(persistence.message.contains("Durable data could not be observed"))

            sourceOfTruth.recoverReaderWith("durable")
            assertEquals("durable", store.get(TestKey("key"), Freshness.LocalOnly))
        } finally {
            store.close()
        }
    }

    @Test
    fun fetchedWriteFailure_isTypedPersistenceAndDoesNotInstallTheFetchedValue() = runTest {
        val boom = IllegalStateException("write rejected")
        val sourceOfTruth = AdapterFailureSourceOfTruth(writeFailure = boom)
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "fetched" }
        }

        try {
            val failure = assertFailsWith<StoreException> { store.get(key) }
            val persistence = assertIs<StoreError.Persistence>(failure.error)
            assertTrue(persistence.cause === boom)
            assertTrue(persistence.message.contains("source of truth rejected the write"))

            val missing =
                assertFailsWith<StoreException> {
                    store.get(key, Freshness.LocalOnly)
                }
            assertIs<StoreError.Missing>(missing.error)
        } finally {
            store.close()
        }
    }

    @Test
    fun writeFailureWithQueuedClear_leavesBookkeepingForgotten() = runTest {
        val sourceOfTruth = GatedFailingWriteSourceOfTruth()
        val bookkeeper = InMemoryBookkeeper()
        val key = TestKey("key")
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcher { "fetched" }
        }

        try {
            val read =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { store.get(key) }
                }
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) { sourceOfTruth.writeStarted.await() }
            }
            val clear =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.clear(key)
                }
            runCurrent()

            sourceOfTruth.releaseWrite.complete(Unit)
            val failure = assertIs<StoreException>(read.await().exceptionOrNull())
            assertIs<StoreError.Persistence>(failure.error)
            clear.await()
            assertNull(bookkeeper.status(KeyId.from(key)))
        } finally {
            sourceOfTruth.releaseWrite.complete(Unit)
            store.close()
        }
    }

    @Test
    fun clearDeleteFailure_isTypedAndLeavesTheResidentValueIntact() = runTest {
        val boom = IllegalStateException("delete rejected")
        val sourceOfTruth = AdapterFailureSourceOfTruth()
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "seed" }
        }

        try {
            assertEquals("seed", store.get(key))
            sourceOfTruth.deleteFailure = boom

            val failure = assertFailsWith<StoreException> { store.clear(key) }
            val persistence = assertIs<StoreError.Persistence>(failure.error)
            assertTrue(persistence.cause === boom)
            assertTrue(persistence.message.contains("retry clear()"))
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
        } finally {
            store.close()
        }
    }

    @Test
    fun successfulReturnConvergesFinalWriterOverMutationEraIntermediate() = runTest {
        val sourceOfTruth = GatedWriteSourceOfTruth()
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "fetched" }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            app.cash.turbine.turbineScope {
                val collector = store.stream(key).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                withContext(Dispatchers.Default) {
                    withTimeout(2_000L) { sourceOfTruth.writeStarted.await() }
                }

                sourceOfTruth.publishExternal("external")
                runCurrent()
                collector.expectNoEvents()
                sourceOfTruth.releaseWrite.complete(Unit)
                runCurrent()
                val committed = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("fetched", committed.value)
                assertEquals(Origin.FETCHER, committed.origin)
                assertEquals("fetched", store.get(key, Freshness.LocalOnly))
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseWrite.complete(Unit)
            store.close()
        }
    }

    private class FailingReaderSourceOfTruth : SourceOfTruth<TestKey, String> {
        private val rows = MutableStateFlow<String?>(null)
        private var factoryFailuresRemaining: Int = 0
        private var collectionFailuresRemaining: Int = 0
        private var emitBeforeFirstCollectionFailure: Boolean = false
        private var trackingRecovery: Boolean = false
        var recovered: CompletableDeferred<Unit> = CompletableDeferred()
            private set
        var readerCalls: Int = 0

        fun beginFactoryOutage(failures: Int) {
            factoryFailuresRemaining = failures
            trackingRecovery = true
            recovered = CompletableDeferred()
            readerCalls = 0
        }

        fun beginCollectionOutage(
            failures: Int,
            emitBeforeFirstFailure: Boolean,
        ) {
            collectionFailuresRemaining = failures
            emitBeforeFirstCollectionFailure = emitBeforeFirstFailure
            trackingRecovery = true
            recovered = CompletableDeferred()
            readerCalls = 0
        }

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (factoryFailuresRemaining > 0) {
                factoryFailuresRemaining -= 1
                throw IllegalStateException("reader factory failed")
            }
            val shouldFail = collectionFailuresRemaining > 0
            val emitFirst = emitBeforeFirstCollectionFailure && collectionFailuresRemaining == 3
            if (shouldFail) collectionFailuresRemaining -= 1
            if (!shouldFail && trackingRecovery) {
                recovered.complete(Unit)
                trackingRecovery = false
            }
            return flow {
                if (emitFirst) emit(rows.value)
                if (shouldFail) throw IllegalStateException("reader collection failed")
                rows.collect { emit(it) }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class GatedWriteSourceOfTruth : SourceOfTruth<TestKey, String> {
        private val rows = MutableStateFlow<String?>("seed")
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return flow {
                rows.collect { row ->
                    emit(row)
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
            writeStarted.complete(Unit)
            releaseWrite.await()
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }

        fun publishExternal(value: String) {
            rows.value = value
        }
    }

    private class GatedFailingWriteSourceOfTruth : SourceOfTruth<TestKey, String> {
        private val rows = MutableStateFlow<String?>(null)
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeStarted.complete(Unit)
            releaseWrite.await()
            throw IllegalStateException("write rejected")
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class AdapterFailureSourceOfTruth(
        private var readerFailure: Throwable? = null,
        private var writeFailure: Throwable? = null,
        var deleteFailure: Throwable? = null,
    ) : SourceOfTruth<TestKey, String> {
        private val rows = MutableStateFlow<String?>(null)

        override fun reader(key: TestKey): Flow<String?> {
            readerFailure?.let { throw it }
            return rows
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeFailure?.let { throw it }
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            deleteFailure?.let { throw it }
            rows.value = null
        }

        fun recoverReaderWith(value: String) {
            rows.value = value
            readerFailure = null
        }
    }
}
