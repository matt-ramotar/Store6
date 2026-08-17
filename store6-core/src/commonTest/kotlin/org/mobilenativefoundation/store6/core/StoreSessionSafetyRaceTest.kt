package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class StoreSessionSafetyRaceTest {
    @Test
    fun clearEveryOldStore_fencesPreClearTicketsAcrossStores() = runTest(timeout = 60.seconds) {
        val usersBacking = InMemorySourceOfTruth<TestKey, String>()
        val documentsBacking = InMemorySourceOfTruth<TestKey, String>()
        val usersPersistence = PostDeleteGateSourceOfTruth(usersBacking)
        val documentsPersistence = PostDeleteGateSourceOfTruth(documentsBacking)
        val usersFetchStarted = CompletableDeferred<Unit>()
        val documentsFetchStarted = CompletableDeferred<Unit>()
        val releaseUsersFetch = CompletableDeferred<Unit>()
        val releaseDocumentsFetch = CompletableDeferred<Unit>()
        var usersFetches = 0
        var documentsFetches = 0
        val users =
            store<TestKey, String> {
                fetcher {
                    when (++usersFetches) {
                        1 -> "users-seed"
                        2 -> {
                            usersFetchStarted.complete(Unit)
                            releaseUsersFetch.await()
                            "users-doomed"
                        }
                        else -> error("unexpected users fetch $usersFetches")
                    }
                }
                persistence(usersPersistence)
            }
        val documents =
            store<TestKey, String> {
                fetcher {
                    when (++documentsFetches) {
                        1 -> "documents-seed"
                        2 -> {
                            documentsFetchStarted.complete(Unit)
                            releaseDocumentsFetch.await()
                            "documents-doomed"
                        }
                        else -> error("unexpected documents fetch $documentsFetches")
                    }
                }
                persistence(documentsPersistence)
            }
        val usersKey = TestKey("users")
        val documentsKey = TestKey("documents")

        try {
            assertEquals("users-seed", users.get(usersKey))
            assertEquals("documents-seed", documents.get(documentsKey))
            val usersTail =
                backgroundScope.async {
                    runCatching { users.get(usersKey, Freshness.MustBeFresh) }
                }
            val documentsTail =
                backgroundScope.async {
                    runCatching { documents.get(documentsKey, Freshness.MustBeFresh) }
                }
            runCurrent()
            awaitFromDefault {
                usersFetchStarted.await()
                documentsFetchStarted.await()
            }

            val usersClear = backgroundScope.async { users.clearAll() }
            val documentsClear = backgroundScope.async { documents.clearAll() }
            runCurrent()
            awaitFromDefault {
                usersPersistence.allDeleted.await()
                documentsPersistence.allDeleted.await()
            }

            releaseUsersFetch.complete(Unit)
            releaseDocumentsFetch.complete(Unit)
            usersPersistence.releaseDelete.complete(Unit)
            documentsPersistence.releaseDelete.complete(Unit)
            awaitFromDefault {
                usersClear.await()
                documentsClear.await()
            }

            assertMissing(awaitFromDefault { usersTail.await() })
            assertMissing(awaitFromDefault { documentsTail.await() })
            assertNull(usersBacking.reader(usersKey).first())
            assertNull(documentsBacking.reader(documentsKey).first())
            assertEquals(2, usersFetches)
            assertEquals(2, documentsFetches)
        } finally {
            releaseUsersFetch.complete(Unit)
            releaseDocumentsFetch.complete(Unit)
            usersPersistence.releaseDelete.complete(Unit)
            documentsPersistence.releaseDelete.complete(Unit)
            users.closeAndSettleForTest()
            documents.closeAndSettleForTest()
        }
    }

    @Test
    fun orderedReplacement_rejectsAuthRaceDemandAndPreventsOldSessionCommits() =
        runTest(timeout = 60.seconds) {
            val events = mutableListOf<String>()
            var authActive = true
            var journalActive = true
            var storageOpen = true
            var replacementPublished = false
            val usersBookkeeper = RecordingBookkeeper()
            val documentsBookkeeper = RecordingBookkeeper()
            val usersBacking = InMemorySourceOfTruth<TestKey, String>()
            val documentsBacking = InMemorySourceOfTruth<TestKey, String>()
            val usersFetch = CooperativeFetchProbe { authActive }
            val documentsFetch = CooperativeFetchProbe { authActive }
            val users =
                store<TestKey, String> {
                    fetcher { usersFetch.fetch() }
                    persistence(usersBacking)
                    bookkeeper(usersBookkeeper)
                }
            val documents =
                store<TestKey, String> {
                    fetcher { documentsFetch.fetch() }
                    persistence(documentsBacking)
                    bookkeeper(documentsBookkeeper)
                }
            val demand = SessionDemandGate(backgroundScope, events)
            val usersKey = TestKey("users")
            val documentsKey = TestKey("documents")
            val usersCollector =
                demand.launch {
                    users.stream(usersKey, Freshness.MustBeFresh).collect()
                }
            val documentsCollector =
                demand.launch {
                    documents.stream(documentsKey, Freshness.MustBeFresh).collect()
                }
            awaitFromDefault {
                usersFetch.started.await()
                documentsFetch.started.await()
            }
            val demandAttempted = CompletableDeferred<Unit>()
            val deniedDemand =
                backgroundScope.async {
                    demand.closed.await()
                    runCatching {
                        demand.launch { users.get(usersKey, Freshness.MustBeFresh) }
                    }.also {
                        assertTrue(authActive, "the application gate must reject before auth revoke")
                        demandAttempted.complete(Unit)
                    }
                }

            replaceOldSession(
                demand = demand,
                stores = listOf(NamedStore("users", users), NamedStore("documents", documents)),
                beforeAuthRevoke = { awaitFromDefault { demandAttempted.await() } },
                revokeAuth = { authActive = false },
                quarantineJournal = { journalActive = false },
                closeStorage = { storageOpen = false },
                publishReplacement = { replacementPublished = true },
                events = events,
            )

            val denied = assertIs<IllegalStateException>(deniedDemand.await().exceptionOrNull())
            assertEquals("Old session demand is closed.", denied.message)
            assertTrue(usersCollector.isCancelled)
            assertTrue(documentsCollector.isCancelled)
            assertTrue(usersFetch.exited.isCompleted)
            assertTrue(documentsFetch.exited.isCompleted)
            assertEquals(1, usersFetch.starts)
            assertEquals(1, documentsFetch.starts)
            assertEquals(0, usersBookkeeper.log.count { it.startsWith("recordSuccess:") })
            assertEquals(0, documentsBookkeeper.log.count { it.startsWith("recordSuccess:") })
            assertNull(usersBacking.reader(usersKey).first())
            assertNull(documentsBacking.reader(documentsKey).first())
            assertFalse(authActive)
            assertFalse(journalActive)
            assertFalse(storageOpen)
            assertTrue(replacementPublished)
            assertEquals(
                listOf(
                    "demand-gate-closed",
                    "workers-joined",
                    "auth-revoked",
                    "journal-quarantined",
                    "clear-users-started",
                    "clear-users-completed",
                    "clear-documents-started",
                    "clear-documents-completed",
                    "close-users",
                    "close-documents",
                    "storage-closed",
                    "replacement-published",
                ),
                events,
            )
        }

    @Test
    fun cleanupFailure_closesEveryOldStoreAndDoesNotPublishReplacement() =
        runTest(timeout = 60.seconds) {
            val events = mutableListOf<String>()
            val failedPersistence =
                RecordingSourceOfTruth(InMemorySourceOfTruth<TestKey, String>()).also {
                    it.deleteAllFailure = IllegalStateException("users delete unavailable")
                }
            val documentsPersistence =
                RecordingSourceOfTruth(InMemorySourceOfTruth<TestKey, String>())
            val users =
                store<TestKey, String> {
                    fetcher { "users" }
                    persistence(failedPersistence)
                }
            val documents =
                store<TestKey, String> {
                    fetcher { "documents" }
                    persistence(documentsPersistence)
                }
            val demand = SessionDemandGate(backgroundScope, events)
            var authActive = true
            var journalActive = true
            var storageOpen = true
            var replacementPublished = false

            val failure =
                runCatching {
                    replaceOldSession(
                        demand = demand,
                        stores =
                            listOf(
                                NamedStore("users", users),
                                NamedStore("documents", documents),
                            ),
                        revokeAuth = { authActive = false },
                        quarantineJournal = { journalActive = false },
                        closeStorage = { storageOpen = false },
                        publishReplacement = { replacementPublished = true },
                        events = events,
                    )
                }.exceptionOrNull()

            assertIs<StoreException>(failure)
            assertEquals(listOf("deleteAll"), documentsPersistence.log)
            assertFalse(authActive)
            assertFalse(journalActive)
            assertFalse(storageOpen)
            assertFalse(replacementPublished)
            assertTrue(events.contains("clear-users-failed"))
            assertTrue(events.contains("clear-documents-completed"))
            assertTrue(events.contains("close-users"))
            assertTrue(events.contains("close-documents"))
            assertTrue(events.contains("storage-closed"))
            assertFalse(events.contains("replacement-published"))
            assertClosed(users)
            assertClosed(documents)
        }

    @Test
    fun journalQuarantineFailure_stillClearsAndClosesWithoutPublishingReplacement() =
        runTest(timeout = 60.seconds) {
            val events = mutableListOf<String>()
            val usersPersistence =
                RecordingSourceOfTruth(InMemorySourceOfTruth<TestKey, String>())
            val documentsPersistence =
                RecordingSourceOfTruth(InMemorySourceOfTruth<TestKey, String>())
            val users =
                store<TestKey, String> {
                    fetcher { "users" }
                    persistence(usersPersistence)
                }
            val documents =
                store<TestKey, String> {
                    fetcher { "documents" }
                    persistence(documentsPersistence)
                }
            val demand = SessionDemandGate(backgroundScope, events)
            val expected = IllegalStateException("journal quarantine unavailable")
            var storageOpen = true
            var replacementPublished = false

            try {
                val failure =
                    runCatching {
                        replaceOldSession(
                            demand = demand,
                            stores =
                                listOf(
                                    NamedStore("users", users),
                                    NamedStore("documents", documents),
                                ),
                            revokeAuth = {},
                            quarantineJournal = { throw expected },
                            closeStorage = { storageOpen = false },
                            publishReplacement = { replacementPublished = true },
                            events = events,
                        )
                    }.exceptionOrNull()

                assertEquals(expected, failure)
                assertEquals(listOf("deleteAll"), usersPersistence.log)
                assertEquals(listOf("deleteAll"), documentsPersistence.log)
                assertFalse(storageOpen)
                assertFalse(replacementPublished)
                assertTrue(events.contains("journal-quarantine-failed"))
                assertTrue(events.contains("clear-users-completed"))
                assertTrue(events.contains("clear-documents-completed"))
                assertTrue(events.contains("close-users"))
                assertTrue(events.contains("close-documents"))
                assertTrue(events.contains("storage-closed"))
                assertFalse(events.contains("replacement-published"))
                assertClosed(users)
                assertClosed(documents)
            } finally {
                users.closeAndSettleForTest()
                documents.closeAndSettleForTest()
            }
        }

    private fun assertMissing(result: Result<String>) {
        val failure = assertIs<StoreException>(result.exceptionOrNull())
        assertIs<StoreError.Missing>(failure.error)
    }

    private suspend fun assertClosed(store: Store<TestKey, String>) {
        val failure = runCatching { store.get(TestKey("post-close")) }.exceptionOrNull()
        assertEquals("Store is closed.", assertIs<IllegalStateException>(failure).message)
    }
}

private data class NamedStore(
    val name: String,
    val store: Store<*, *>,
)

private class SessionDemandGate(
    parentScope: CoroutineScope,
    private val events: MutableList<String>,
) {
    private val lock = Mutex()
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private var accepting = true
    val closed = CompletableDeferred<Unit>()

    suspend fun <T> launch(block: suspend () -> T): Deferred<T> =
        lock.withLock {
            check(accepting) { "Old session demand is closed." }
            scope.async(start = CoroutineStart.UNDISPATCHED) { block() }
        }

    suspend fun closeAndJoin() {
        lock.withLock {
            accepting = false
            events += "demand-gate-closed"
            closed.complete(Unit)
        }
        job.cancelAndJoin()
        events += "workers-joined"
    }
}

private class CooperativeFetchProbe(
    private val authActive: () -> Boolean,
) {
    val started = CompletableDeferred<Unit>()
    val exited = CompletableDeferred<Unit>()
    var starts: Int = 0
        private set

    suspend fun fetch(): String {
        starts += 1
        check(authActive()) { "old auth was already revoked" }
        started.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            exited.complete(Unit)
        }
    }
}

private suspend fun replaceOldSession(
    demand: SessionDemandGate,
    stores: List<NamedStore>,
    beforeAuthRevoke: suspend () -> Unit = {},
    revokeAuth: () -> Unit,
    quarantineJournal: () -> Unit,
    closeStorage: () -> Unit,
    publishReplacement: () -> Unit,
    events: MutableList<String>,
) {
    demand.closeAndJoin()
    beforeAuthRevoke()
    var cleanupFailure: Throwable? = null
    try {
        revokeAuth()
        events += "auth-revoked"
    } catch (failure: Throwable) {
        events += "auth-revoke-failed"
        cleanupFailure = failure
    }
    try {
        quarantineJournal()
        events += "journal-quarantined"
    } catch (failure: Throwable) {
        events += "journal-quarantine-failed"
        if (cleanupFailure == null) cleanupFailure = failure
    }

    stores.forEach { named ->
        events += "clear-${named.name}-started"
        try {
            named.store.clearAll()
            events += "clear-${named.name}-completed"
        } catch (failure: Throwable) {
            events += "clear-${named.name}-failed"
            if (cleanupFailure == null) cleanupFailure = failure
        }
    }
    stores.forEach { named ->
        try {
            named.store.closeAndSettleForTest()
            events += "close-${named.name}"
        } catch (failure: Throwable) {
            events += "close-${named.name}-failed"
            if (cleanupFailure == null) cleanupFailure = failure
        }
    }
    try {
        closeStorage()
        events += "storage-closed"
    } catch (failure: Throwable) {
        events += "storage-close-failed"
        if (cleanupFailure == null) cleanupFailure = failure
    }

    cleanupFailure?.let { throw it }
    publishReplacement()
    events += "replacement-published"
}

private suspend fun <T> awaitFromDefault(block: suspend () -> T): T =
    withContext(Dispatchers.Default) {
        block()
    }
