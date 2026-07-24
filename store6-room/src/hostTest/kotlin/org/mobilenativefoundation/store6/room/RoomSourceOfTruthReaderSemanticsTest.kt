@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.room

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

internal class RoomSourceOfTruthReaderSemanticsTest {
    private val keyA = RoomKitKey(StoreNamespace("users"), "a")
    private val keyB = RoomKitKey(StoreNamespace("users"), "b")

    @Test
    fun sameTableOtherRowWrite_doesNotReemit(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val equalityObserved = CompletableDeferred<Unit>()
            val sourceOfTruth = equalityProbeSourceOfTruth(database, equalityObserved)
            dao.upsert(row(keyA, "value-a"))

            sourceOfTruth.reader(keyA).test {
                assertEquals("value-a", awaitItem()?.payload)

                dao.upsert(row(keyB, "value-b"))
                equalityObserved.await()
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun externalDaoWrite_isObservedLive(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val sourceOfTruth = sourceOfTruth(database)
            dao.upsert(row(keyA, "value-1"))

            sourceOfTruth.reader(keyA).test {
                assertEquals("value-1", awaitItem())

                dao.upsert(row(keyA, "value-2"))
                assertEquals("value-2", awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun equalValueWriteAfterExternalEqualChange_stillEmits(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val sourceOfTruth = sourceOfTruth(database)

            sourceOfTruth.reader(keyA).test {
                assertNull(awaitItem())

                dao.upsert(row(keyA, "value"))
                assertEquals("value", awaitItem())

                sourceOfTruth.write(keyA, "value")
                assertEquals("value", awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun registrations_removedWhenLastReaderCancels(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)

            turbineScope {
                val first = sourceOfTruth.reader(keyA).testIn(backgroundScope)
                val second = sourceOfTruth.reader(keyA).testIn(backgroundScope)
                try {
                    assertNull(first.awaitItem())
                    assertNull(second.awaitItem())
                } finally {
                    first.cancelAndIgnoreRemainingEvents()
                    second.cancelAndIgnoreRemainingEvents()
                }
            }

            sourceOfTruth.write(keyA, "value")
            sourceOfTruth.reader(keyA).test {
                assertEquals("value", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun writeFailure_rollsBackAndEchoesNothing(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val sourceOfTruth =
                sourceOfTruth(database) { key, value ->
                    dao.upsert(row(key, value))
                    throw IllegalStateException("write failed")
                }

            sourceOfTruth.reader(keyA).test {
                assertNull(awaitItem())

                assertFailsWith<IllegalStateException> {
                    sourceOfTruth.write(keyA, "rolled-back")
                }
                runCurrent()
                expectNoEvents()
                assertNull(dao.row(keyA.namespace.value, keyA.canonicalId()).first())

                dao.upsert(row(keyA, "committed"))
                assertEquals("committed", awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun outerTransaction_nestedAndConcurrentWrites_doNotInvertLocks(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val sourceOfTruth = sourceOfTruth(database)
            lateinit var concurrentWrite: Deferred<Unit>

            sourceOfTruth.withTransaction {
                concurrentWrite =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        sourceOfTruth.write(keyB, "concurrent")
                    }
                sourceOfTruth.write(keyA, "nested")
            }
            concurrentWrite.await()

            assertEquals(
                "nested",
                dao.row(keyA.namespace.value, keyA.canonicalId()).first()?.payload,
            )
            assertEquals(
                "concurrent",
                dao.row(keyB.namespace.value, keyB.canonicalId()).first()?.payload,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun cancelledBackpressuredWrite_publishesEchoAndDoesNotWedgeGate(): TestResult = runTest {
        val databasePath = newTempDatabasePath()
        val database = openTestDatabase(databasePath)
        val externalDatabase = openTestDatabase(databasePath)
        val releaseCollector = CompletableDeferred<Unit>()
        var collector: Job? = null
        var externalCandidateObservation: Deferred<Unit>? = null
        var releaseWrite: Deferred<Unit>? = null
        var queuedWrite: Deferred<Unit>? = null
        var candidateWrite: Deferred<Unit>? = null
        try {
            val dao = database.kitRowDao()
            val externalDao = externalDatabase.kitRowDao()
            assertNull(dao.row(keyA.namespace.value, keyA.canonicalId()).first())
            assertNull(externalDao.row(keyA.namespace.value, keyA.canonicalId()).first())
            val fillMergeBuffer = CompletableDeferred<Unit>()
            val mergeBufferFilled = CompletableDeferred<Unit>()
            val queryExternalValue = CompletableDeferred<Unit>()
            val sourceOfTruth =
                backpressureProbeSourceOfTruth(
                    database = database,
                    fillMergeBuffer = fillMergeBuffer,
                    mergeBufferFilled = mergeBufferFilled,
                    queryExternalValue = queryExternalValue,
                )
            val initialObserved = CompletableDeferred<Unit>()
            val blockedEchoObserved = CompletableDeferred<Unit>()
            val candidateEchoObserved = CompletableDeferred<Unit>()
            val externalChangeObserved = CompletableDeferred<Unit>()
            val blockingValue = EchoProbeValue("block")
            val candidateValue = EchoProbeValue("candidate")

            collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    sourceOfTruth.reader(keyA).collect { value ->
                        when {
                            value == null -> initialObserved.complete(Unit)
                            value === blockingValue -> {
                                blockedEchoObserved.complete(Unit)
                                releaseCollector.await()
                            }
                            value === candidateValue -> candidateEchoObserved.complete(Unit)
                            value.payload == "external" -> externalChangeObserved.complete(Unit)
                        }
                    }
                }
            initialObserved.await()

            sourceOfTruth.write(keyA, blockingValue)
            blockedEchoObserved.await()
            fillMergeBuffer.complete(Unit)
            mergeBufferFilled.await()

            sourceOfTruth.write(keyA, EchoProbeValue("in-flight"))
            runCurrent()
            repeat(64) { index ->
                sourceOfTruth.write(keyA, EchoProbeValue("buffer-$index"))
            }

            externalCandidateObservation =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    while (
                        externalDao
                            .row(keyA.namespace.value, keyA.canonicalId())
                            .first()
                            ?.payload != "candidate"
                    ) {
                        yield()
                    }
                }
            candidateWrite =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    sourceOfTruth.write(keyA, candidateValue)
                }
            externalCandidateObservation.await()
            runCurrent()
            assertFalse(
                candidateWrite.isCompleted,
                "the candidate echo must be backpressured by the saturated buffer",
            )

            candidateWrite.cancel()
            runCurrent()
            queuedWrite =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    sourceOfTruth.write(keyB, EchoProbeValue("queued"))
                }
            releaseWrite =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    dao.upsert(row(keyB, "release"))
                    releaseCollector.complete(Unit)
                }
            releaseWrite.await()
            candidateEchoObserved.await()
            candidateWrite.join()
            assertEquals(
                "candidate",
                dao.row(keyA.namespace.value, keyA.canonicalId()).first()?.payload,
                "the candidate transaction must remain committed after writer cancellation",
            )

            queuedWrite.await()
            assertEquals(
                "queued",
                dao.row(keyB.namespace.value, keyB.canonicalId()).first()?.payload,
            )
            externalDao.upsert(row(keyA, "external"))
            queryExternalValue.complete(Unit)
            externalChangeObserved.await()
        } finally {
            releaseCollector.complete(Unit)
            candidateWrite?.cancel()
            externalCandidateObservation?.cancel()
            queuedWrite?.cancel()
            releaseWrite?.cancel()
            collector?.cancel()
            externalDatabase.close()
            database.close()
            candidateWrite?.join()
            externalCandidateObservation?.join()
            queuedWrite?.join()
            releaseWrite?.join()
            collector?.join()
        }
    }

    private fun sourceOfTruth(
        database: Store6RoomTestDatabase,
        rowWriter: (suspend (RoomKitKey, String) -> Unit)? = null,
    ): RoomSourceOfTruth<RoomKitKey, String> {
        val dao = database.kitRowDao()
        val writer: suspend (RoomKitKey, String) -> Unit =
            rowWriter ?: { key, value ->
                dao.upsert(row(key, value))
            }
        return RoomSourceOfTruth(
            database = database,
            rowReader = { key ->
                dao.row(key.namespace.value, key.canonicalId()).map { it?.payload }
            },
            rowWriter = writer,
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

    private fun backpressureProbeSourceOfTruth(
        database: Store6RoomTestDatabase,
        fillMergeBuffer: CompletableDeferred<Unit>,
        mergeBufferFilled: CompletableDeferred<Unit>,
        queryExternalValue: CompletableDeferred<Unit>,
    ): RoomSourceOfTruth<RoomKitKey, EchoProbeValue> {
        val dao = database.kitRowDao()
        return RoomSourceOfTruth(
            database = database,
            rowReader = { key ->
                kotlinx.coroutines.flow.flow {
                    suspend fun currentValue(): EchoProbeValue? =
                        dao
                            .row(key.namespace.value, key.canonicalId())
                            .first()
                            ?.let { EchoProbeValue(it.payload) }

                    emit(currentValue())
                    fillMergeBuffer.await()
                    repeat(64) { index ->
                        emit(EchoProbeValue("database-buffer-$index"))
                    }
                    mergeBufferFilled.complete(Unit)
                    emit(EchoProbeValue("database-buffer-in-flight"))
                    queryExternalValue.await()
                    emit(currentValue())
                }
            },
            rowWriter = { key, value ->
                dao.upsert(row(key, value.payload))
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

    private fun equalityProbeSourceOfTruth(
        database: Store6RoomTestDatabase,
        equalityObserved: CompletableDeferred<Unit>,
    ): RoomSourceOfTruth<RoomKitKey, EqualityProbeValue> {
        val dao = database.kitRowDao()
        return RoomSourceOfTruth(
            database = database,
            rowReader = { key ->
                dao.row(key.namespace.value, key.canonicalId()).map { entity ->
                    entity?.let { EqualityProbeValue(it.payload, equalityObserved) }
                }
            },
            rowWriter = { key, value ->
                dao.upsert(row(key, value.payload))
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

    private fun row(
        key: RoomKitKey,
        value: String,
    ): KitRowEntity =
        KitRowEntity(
            namespace = key.namespace.value,
            id = key.canonicalId(),
            payload = value,
        )

    private class EqualityProbeValue(
        val payload: String,
        private val equalityObserved: CompletableDeferred<Unit>,
    ) {
        override fun equals(other: Any?): Boolean {
            val equal = other is EqualityProbeValue && payload == other.payload
            if (equal) {
                equalityObserved.complete(Unit)
            }
            return equal
        }

        override fun hashCode(): Int = payload.hashCode()
    }

    private data class EchoProbeValue(
        val payload: String,
    )
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
