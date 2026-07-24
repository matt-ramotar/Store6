@file:OptIn(org.mobilenativefoundation.store6.core.DelicateStoreApi::class)

package org.mobilenativefoundation.store6.room

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.TransactionalSourceOfTruth
import kotlin.coroutines.CoroutineContext

/**
 * [TransactionalSourceOfTruth] over user DAO lambdas backed by one [RoomDatabase].
 *
 * Reader semantics on Room's table-granular InvalidationTracker use a generation-gated echo.
 * Every mutation through this instance allocates a per-key generation before its transaction
 * commits, commits, then publishes a generation-stamped echo. Each collection captures a
 * generation baseline when its echo subscription is established, drops database re-emissions
 * observed while an allocated generation is still unconsumed, equality-suppresses database
 * signals otherwise, and emits every echo newer than its baseline.
 *
 * This makes equal-value rewrites through this instance re-emit exactly once and prevents writes
 * to another row in the same table from re-emitting an unchanged value. Equality is structural on
 * [V]; identity-equality values can therefore surface duplicate unchanged-row emissions after
 * same-table writes. Those duplicates are safe for the engine's conflation but noisier, so value
 * types are preferred. Echo publication uses a bounded suspending buffer: a starved collector
 * backpressures writers instead of dropping a mutation or leaving the generation gate open. Once
 * a top-level mutation returns committed, the Room writer is released while mutation admission
 * remains held, then its echo finishes under [NonCancellable]. Cancellation is propagated after
 * ordered echo publication and admission release.
 *
 * External changes through other database handles surface from the Room query while collected
 * and in every new collection's first emission. An external change racing a mutation through this
 * instance on the same key can be coalesced into that mutation's echo or the next re-query. One
 * theoretical stall window remains: a query can snapshot before a mutation, then delay delivery
 * until after that mutation's echo is consumed, surfacing one stale emission before the mutation's
 * own invalidation re-query self-heals it. If observed, the escalation is a per-key version stamp
 * written in the transaction and joined into the reader query.
 *
 * Top-level mutations and [withTransaction] acquire mutation admission before a Room writer. A
 * private coroutine-context lease makes same-instance nested calls reentrant: sequential nested
 * writes bypass admission reacquisition and enlist in the current Room transaction. Queued
 * top-level mutations therefore wait without retaining a Room writer. Operations sharing an
 * inherited lease are expected to remain sequential, matching Room transaction usage.
 *
 * Present behavior: cancellation exactly at the commit boundary can commit and still surface
 * `CancellationException`; the adapter then rolls back the generation allocation and publishes no
 * echo, while readers converge through the commit's invalidation re-query. The engine treats the
 * throw as not applied and re-hydrates conservatively, matching the TD-6 crash-window posture.
 * When [withTransaction] wraps nested writes for the future TD-11 mutations decorator, nested
 * mutations enlist in the outer transaction and their echoes publish before that outer commit;
 * the decorator owns reader gating across that window. Transaction-scoped echo buffering is
 * deliberately not implemented here.
 *
 * Freeze candidate: issue 007 has landed; the seam freezes only after Matt signs the prepared
 * sign-off package.
 */
@ExperimentalStoreApi
public class RoomSourceOfTruth<K : StoreKey, V : Any>(
    private val database: RoomDatabase,
    private val rowReader: (K) -> Flow<V?>,
    private val rowWriter: suspend (K, V) -> Unit,
    private val rowDeleter: suspend (K) -> Unit,
    private val namespaceDeleter: suspend (StoreNamespace) -> Unit,
    private val allDeleter: suspend () -> Unit,
) : TransactionalSourceOfTruth<K, V> {
    private class MutationAdmissionLease(
        override val key: CoroutineContext.Key<*>,
    ) : CoroutineContext.Element

    private sealed interface Signal<out V> {
        /** Prepended at echo-subscription time; carries the generation floor for a collection. */
        class Baseline(
            val generation: Long,
        ) : Signal<Nothing>

        class FromMutation<V>(
            val generation: Long,
            val value: V?,
        ) : Signal<V>

        class FromDatabase<V>(
            val value: V?,
        ) : Signal<V>
    }

    private class Registration<V> {
        var activeReaders: Int = 0

        /**
         * Highest mutation generation allocated for this key. Written under [mutationOrder] before
         * the transaction commits and rolled back on failure; collectors read it through StateFlow
         * volatile semantics.
         */
        val publishedGeneration: MutableStateFlow<Long> = MutableStateFlow(0L)

        /** Publishes mutation echoes; [Signal.Baseline] is prepended at subscription time. */
        val mutationEchoes: MutableSharedFlow<Signal<V>> =
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.SUSPEND,
            )
    }

    private val registrationsLock = Mutex()
    private val registrations = HashMap<Pair<String, String>, Registration<V>>()

    /** Serializes top-level transactions and their generation-gated echo publication. */
    private val mutationOrder = Mutex()
    private val mutationAdmissionKey =
        object : CoroutineContext.Key<MutationAdmissionLease> {}

    override fun reader(key: K): Flow<V?> =
        flow {
            val id = idOf(key)
            val registration = register(id)
            try {
                var emittedAny = false
                var lastEmitted: V? = null
                // -1 means the echo subscription has not established its baseline yet.
                var lastSeenGeneration = -1L
                merge(
                    registration.mutationEchoes.onSubscription {
                        emit(Signal.Baseline(registration.publishedGeneration.value))
                    },
                    rowReader(key).map { value -> Signal.FromDatabase(value) },
                ).collect { signal ->
                    when (signal) {
                        is Signal.Baseline ->
                            if (signal.generation > lastSeenGeneration) {
                                lastSeenGeneration = signal.generation
                            }
                        is Signal.FromMutation ->
                            if (signal.generation > lastSeenGeneration) {
                                lastSeenGeneration = signal.generation
                                emittedAny = true
                                lastEmitted = signal.value
                                emit(signal.value)
                            }
                        is Signal.FromDatabase -> {
                            val echoInFlight =
                                lastSeenGeneration >= 0L &&
                                    registration.publishedGeneration.value > lastSeenGeneration
                            if (!echoInFlight && (!emittedAny || signal.value != lastEmitted)) {
                                emittedAny = true
                                lastEmitted = signal.value
                                emit(signal.value)
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { unregister(id) }
            }
        }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        mutateAndEcho(
            targets = {
                registrationsLock.withLock {
                    listOfNotNull(registrations[idOf(key)])
                }
            },
            echoedValue = value,
        ) {
            rowWriter(key, value)
        }
    }

    override suspend fun delete(key: K) {
        mutateAndEcho(
            targets = {
                registrationsLock.withLock {
                    listOfNotNull(registrations[idOf(key)])
                }
            },
            echoedValue = null,
        ) {
            rowDeleter(key)
        }
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        mutateAndEcho(
            targets = {
                registrationsLock.withLock {
                    registrations
                        .filterKeys { id -> id.first == namespace.value }
                        .values
                        .toList()
                }
            },
            echoedValue = null,
        ) {
            namespaceDeleter(namespace)
        }
    }

    override suspend fun deleteAll() {
        mutateAndEcho(
            targets = {
                registrationsLock.withLock {
                    registrations.values.toList()
                }
            },
            echoedValue = null,
        ) {
            allDeleter()
        }
    }

    override suspend fun <R> withTransaction(block: suspend () -> R): R =
        withMutationAdmission {
            runInTransaction(block)
        }

    /**
     * Acquires admission before a top-level writer, commits, releases that writer, and publishes
     * ordered echoes while retaining admission. Nested calls reuse their active admission lease.
     */
    private suspend fun mutateAndEcho(
        targets: suspend () -> List<Registration<V>>,
        echoedValue: V?,
        block: suspend () -> Unit,
    ) {
        withMutationAdmission {
            val recipients = targets()
            val generations =
                recipients.map { registration ->
                    val next = registration.publishedGeneration.value + 1L
                    registration.publishedGeneration.value = next
                    next
                }
            try {
                runInTransaction(block)
            } catch (failure: Throwable) {
                recipients.forEach { registration ->
                    registration.publishedGeneration.value -= 1L
                }
                throw failure
            }
            withContext(NonCancellable) {
                recipients.forEachIndexed { index, registration ->
                    registration.mutationEchoes.emit(
                        Signal.FromMutation(generations[index], echoedValue),
                    )
                }
            }
        }
        currentCoroutineContext().ensureActive()
    }

    /**
     * Installs one instance-keyed admission lease. Same-instance nested calls reuse the lease and
     * therefore the Room transaction already bound to their coroutine context.
     */
    private suspend fun <R> withMutationAdmission(block: suspend () -> R): R {
        if (currentCoroutineContext()[mutationAdmissionKey] != null) {
            return block()
        }
        return mutationOrder.withLock {
            withContext(MutationAdmissionLease(mutationAdmissionKey)) {
                block()
            }
        }
    }

    private suspend fun <R> runInTransaction(block: suspend () -> R): R =
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }

    private fun idOf(key: K): Pair<String, String> =
        key.namespace.value to key.canonicalId()

    private suspend fun register(id: Pair<String, String>): Registration<V> =
        registrationsLock.withLock {
            registrations
                .getOrPut(id) { Registration() }
                .also { registration -> registration.activeReaders += 1 }
        }

    private suspend fun unregister(id: Pair<String, String>) {
        registrationsLock.withLock {
            val registration = registrations[id] ?: return@withLock
            registration.activeReaders -= 1
            if (registration.activeReaders == 0) {
                registrations.remove(id)
            }
        }
    }
}
