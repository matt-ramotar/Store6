@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutationClientIdentityTest {
    @Test
    fun independentJournalsNeverEmitTheSameFirstIdempotencyKey() = runTest {
        val mutation = identityMutation()
        val server = FakeBackend()
        val key = MutationsTestKey("shared-entity")
        val first =
            openIdentityStore(
                storage = InMemoryMutationJournalStorage(),
                mutation = mutation,
                server = server,
            )
        val second =
            openIdentityStore(
                storage = InMemoryMutationJournalStorage(),
                mutation = mutation,
                server = server,
            )

        try {
            first.mutate(key, mutation.ref, "first")
            first.drain(key)
            second.mutate(key, mutation.ref, "second")
            second.drain(key)
        } finally {
            first.close()
            second.close()
        }

        assertNotEquals(
            server.receivedPushes[0].idempotencyKey,
            server.receivedPushes[1].idempotencyKey,
        )
    }

    @Test
    fun reopeningAJournalReplaysTheExactClientIdentityAndIdempotencyKey() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutation = identityMutation()
        val server = FakeBackend()
        val key = MutationsTestKey("restart-stable")
        var cancelFirst = true
        server.pushBehavior = { _, value ->
            if (cancelFirst) {
                cancelFirst = false
                throw CancellationException("outcome unknown")
            }
            MutationPresentAck(
                authoritative = value,
                etag = null,
                canonicalKey = null,
            )
        }

        openIdentityStore(storage, mutation, server).use { first ->
            first.mutate(key, mutation.ref, "value")
            assertFailsWith<CancellationException> { first.drain(key) }
        }
        openIdentityStore(storage, mutation, server).use { reopened ->
            reopened.drain()
        }

        assertEquals(2, server.receivedPushes.size)
        assertEquals(
            server.receivedPushes[0].clientId,
            server.receivedPushes[1].clientId,
        )
        assertEquals(
            server.receivedPushes[0].idempotencyKey,
            server.receivedPushes[1].idempotencyKey,
        )
    }

    @Test
    fun explicitClientIdOverrideIsPersistedAndSeparatorSafe() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutation = identityMutation()
        val server = FakeBackend()
        val explicitClientId = "tenant:west|device:42"
        val key = MutationsTestKey("explicit-client")

        openIdentityStore(
            storage = storage,
            mutation = mutation,
            server = server,
            clientId = explicitClientId,
        ).use { store ->
            store.mutate(key, mutation.ref, "value")
            store.drain(key)
        }

        val push = server.receivedPushes.single()
        assertEquals(explicitClientId, push.clientId)
        assertEquals(explicitClientId, server.retirementRequests.single().clientId)
        assertEquals(
            "store6-mutation:v1:21:tenant:west|device:42:1:1",
            push.idempotencyKey,
        )
        assertEquals(
            explicitClientId,
            storage.transaction { transaction ->
                transaction.journalIdentity()?.clientId
            },
        )
    }

    @Test
    fun sameJournalTransportRetryKeepsTheExactGenerationIdempotencyKey() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutation = identityMutation()
        val server = FakeBackend()
        val key = MutationsTestKey("same-journal-retry")
        var failFirst = true
        server.pushBehavior = { _, value ->
            if (failFirst) {
                failFirst = false
                throw IllegalStateException("retry")
            }
            MutationPresentAck(
                authoritative = value,
                etag = null,
                canonicalKey = null,
            )
        }

        openIdentityStore(storage, mutation, server).use { store ->
            store.mutate(key, mutation.ref, "value")
            store.drain(key)
            store.drain(key)
        }

        assertEquals(2, server.receivedPushes.size)
        assertEquals(
            server.receivedPushes[0].idempotencyKey,
            server.receivedPushes[1].idempotencyKey,
        )
        assertEquals(
            server.receivedPushes[0].clientId,
            server.receivedPushes[1].clientId,
        )
    }

    @Test
    fun legacyClientZeroInflightWorkFailsClosedWithoutExplicitReplayOverride() = runTest {
        val storage = InMemoryMutationJournalStorage()
        seedLegacyInflightMutation(storage)
        val mutation = identityMutation()
        val store = openIdentityStore(storage, mutation, FakeBackend())

        try {
            val failure =
                assertFailsWith<IllegalStateException> {
                    store.pendingWrites()
                }
            assertTrue(failure.message.orEmpty().contains("client-0"))
        } finally {
            store.close()
        }

        assertNull(storage.transaction { it.journalIdentity() })
        assertEquals(
            "client-0:1:g1",
            storage.transaction { it.attempts("client-0").single().generationIdempotencyKey },
        )
    }

    @Test
    fun explicitClientZeroOverrideReplaysLegacyInflightKeyWithoutRewritingIt() = runTest {
        val storage = InMemoryMutationJournalStorage()
        seedLegacyInflightMutation(storage)
        val mutation = identityMutation()
        val server = FakeBackend()

        openIdentityStore(
            storage = storage,
            mutation = mutation,
            server = server,
            clientId = "client-0",
        ).use { store ->
            val beforeDrain =
                assertFailsWith<IllegalStateException> {
                    store.mutate(
                        MutationsTestKey("legacy-new-work-before-drain"),
                        mutation.ref,
                        "forbidden",
                    )
                }
            assertTrue(beforeDrain.message.orEmpty().contains("replay-only"))
            store.drain()
            val afterDrain =
                assertFailsWith<IllegalStateException> {
                    store.mutate(
                        MutationsTestKey("legacy-new-work-after-drain"),
                        mutation.ref,
                        "forbidden",
                    )
                }
            assertTrue(afterDrain.message.orEmpty().contains("replay-only"))
        }

        assertEquals("client-0", server.receivedPushes.single().clientId)
        assertEquals("client-0:1:g1", server.receivedPushes.single().idempotencyKey)
        assertEquals(
            "client-0",
            storage.transaction { it.journalIdentity()?.clientId },
        )
    }
}

private class IdentityMutation(
    val registry: MutatorRegistry<MutationsTestKey, String>,
    val ref: MutatorRef<MutationsTestKey, String, String>,
)

private fun identityMutation(): IdentityMutation {
    lateinit var ref: MutatorRef<MutationsTestKey, String, String>
    val registry =
        mutatorRegistry<MutationsTestKey, String> {
            ref =
                upsert(
                    id = "client-identity-upsert",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { _, value -> MutationPresence.Present(value) }
        }
    return IdentityMutation(registry, ref)
}

private fun openIdentityStore(
    storage: InMemoryMutationJournalStorage,
    mutation: IdentityMutation,
    server: MutationServer<MutationsTestKey, String>,
    clientId: String? = null,
): MutationStore<MutationsTestKey, String> =
    mutationStore(
        registry = mutation.registry,
        server = server,
        keyResolver = MutationsTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
    ) {
        fetcher { "base" }
        journalStorage(storage)
        clientId?.let(::journalClientId)
    }

private suspend fun seedLegacyInflightMutation(storage: InMemoryMutationJournalStorage) {
    storage.transaction { transaction ->
        transaction.insertClient(
            MutationClientRecord(
                recordVersion = 1,
                clientId = "client-0",
                lastAllocatedSequence = 0L,
                retiredThroughSequence = 0L,
                serverConfirmedRetiredThroughSequence = 0L,
                createdAt = 1L,
            ),
        )
        transaction.advanceClient(
            MutationClientRecord(
                recordVersion = 1,
                clientId = "client-0",
                lastAllocatedSequence = 1L,
                retiredThroughSequence = 0L,
                serverConfirmedRetiredThroughSequence = 0L,
                createdAt = 1L,
            ),
        )
        transaction.insertIntent(
            recordVersion = 1,
            clientId = "client-0",
            clientSequence = 1L,
            mutationId = "mutation-1",
            namespace = "mutations",
            canonicalId = "legacy-inflight",
            mutatorId = "client-identity-upsert",
            mutatorVersion = 1,
            argsBlob = "legacy".encodeToByteArray(),
            idempotencyRoot = "client-0:1",
            createdAt = 1L,
        )
        transaction.insertExecution(
            MutationExecutionRecord(
                clientId = "client-0",
                clientSequence = 1L,
                phase = MutationExecutionPhase.UNPREPARED,
                currentGeneration = 0,
                attempt = 0,
                lastAttemptAt = null,
                activeFailureId = null,
                retiredAt = null,
            ),
        )
        transaction.insertAttempt(
            MutationAttemptRecord(
                clientId = "client-0",
                clientSequence = 1L,
                generation = 1,
                effectiveNamespace = "mutations",
                effectiveCanonicalId = "legacy-inflight",
                valueCodecVersion = 1,
                basePresence = MutationPresenceState.PRESENT,
                baseBlob = "base".encodeToByteArray(),
                minePresence = MutationPresenceState.PRESENT,
                mineBlob = "legacy".encodeToByteArray(),
                preconditionMetaPresent = false,
                preconditionWrittenAt = null,
                preconditionEtag = null,
                advertisedRetiredThroughSequence = 0L,
                generationIdempotencyKey = "client-0:1:g1",
                preparedAt = 2L,
                conflictMetaPresent = null,
                conflictWrittenAt = null,
                conflictEtag = null,
                conflictReceivedAt = null,
            ),
        )
        transaction.advanceExecution(
            MutationExecutionRecord(
                clientId = "client-0",
                clientSequence = 1L,
                phase = MutationExecutionPhase.READY,
                currentGeneration = 1,
                attempt = 0,
                lastAttemptAt = null,
                activeFailureId = null,
                retiredAt = null,
            ),
        )
        transaction.advanceExecution(
            MutationExecutionRecord(
                clientId = "client-0",
                clientSequence = 1L,
                phase = MutationExecutionPhase.INFLIGHT,
                currentGeneration = 1,
                attempt = 0,
                lastAttemptAt = null,
                activeFailureId = null,
                retiredAt = null,
            ),
        )
    }
}

private inline fun <K : org.mobilenativefoundation.store6.core.StoreKey, V : Any, R>
    MutationStore<K, V>.use(block: (MutationStore<K, V>) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }
