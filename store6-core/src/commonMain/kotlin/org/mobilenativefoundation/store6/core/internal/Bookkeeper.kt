package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.StoreMeta

/**
 * Engine-owned metadata with deliberate identity equality.
 *
 * A NotModified response creates a new instance so StateFlow re-emits even when the metadata
 * values match the previous successful response.
 */
internal class EngineStoreMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

/**
 * Tracks successful metadata and consecutive fetch failures for each canonical key.
 *
 * This interface is a freeze candidate for issue 008; durable implementations arrive in issue
 * 006. A bookkeeper is a leaf-level lock owner, and its lock must never be co-held with an
 * engine's `stateLock`.
 *
 * Fetch-path [recordSuccess] and [recordFailure], plus operational per-key [forget], are
 * operationally infallible: implementations absorb or report their own storage failures and do
 * not throw them through this interface. Cooperative cancellation may still propagate as
 * `CancellationException`; implementations must not suppress it.
 *
 * The maintenance methods [markStale], [advanceStaleWatermark], [advanceGlobalStaleWatermark],
 * [forgetNamespace], and [forgetAll] may report storage failures by throwing. Each is
 * exception-atomic for every [Throwable], including `CancellationException`: normal return means
 * the full operation was applied, while throwing means it had no effect.
 */
internal interface Bookkeeper {
    suspend fun recordSuccess(
        key: KeyId,
        meta: StoreMeta,
    )

    suspend fun recordFailure(
        key: KeyId,
        atEpochMillis: Long,
    )

    suspend fun status(key: KeyId): KeyStatus?

    suspend fun forget(key: KeyId)

    /** Marks [key] durably stale as one exception-atomic, fallible maintenance operation. */
    suspend fun markStale(key: KeyId)

    /**
     * Advances durable stale coverage for every key in [namespace] as one exception-atomic,
     * fallible maintenance operation.
     */
    suspend fun advanceStaleWatermark(namespace: String)

    /** Advances global durable stale coverage as one exception-atomic, fallible operation. */
    suspend fun advanceGlobalStaleWatermark()

    /**
     * Forgets key records in [namespace] without resetting watermarks as one exception-atomic,
     * fallible maintenance operation.
     */
    suspend fun forgetNamespace(namespace: String)

    /**
     * Forgets all key records without resetting watermarks as one exception-atomic, fallible
     * maintenance operation.
     */
    suspend fun forgetAll()
}

/** Immutable bookkeeping state for one canonical key. */
internal class KeyStatus(
    val meta: StoreMeta?,
    val lastSuccessSequence: Long?,
    val lastFailureAtEpochMillis: Long?,
    val consecutiveFailures: Int,
    /** Whether a key, namespace, or global stale sequence outranks this key's latest success. */
    val durablyStale: Boolean,
)

/**
 * Volatile in-memory [Bookkeeper] with one store-local sequence for successes, marks, and
 * watermarks.
 *
 * This implementation only simulates durable staleness while clients share this instance. Its
 * semantics require retaining the instance, its per-key records, and its namespace/global
 * watermarks for the store lifetime; reconstructing it loses that history. A persistent
 * implementation must durably retain the same information.
 *
 * The sequence supports [Long.MAX_VALUE] successful sequenced operations. A subsequent attempt
 * fails before mutation instead of wrapping; exhaustion is a practical-lifetime invariant
 * failure, not a recoverable storage failure.
 */
internal class InMemoryBookkeeper(
    private val beforeMaintenancePublishTestGate: () -> Unit = {},
    initialSequence: Long = 0L,
) : Bookkeeper {
    init {
        require(initialSequence >= 0L) { "Bookkeeper sequence must not be negative" }
    }

    private class Record(
        val meta: StoreMeta?,
        val lastSuccessSequence: Long?,
        val lastFailureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
        val staleSequence: Long?,
        var cachedStatus: KeyStatus? = null,
    )

    private val lock = Mutex()
    private var records = HashMap<KeyId, Record>()
    private var namespaceStaleWatermarks = HashMap<String, Long>()
    private var globalStaleWatermark = 0L
    private var sequence = initialSequence
    private val watermarkOnlyStatus =
        KeyStatus(
            meta = null,
            lastSuccessSequence = null,
            lastFailureAtEpochMillis = null,
            consecutiveFailures = 0,
            durablyStale = true,
        )

    override suspend fun recordSuccess(
        key: KeyId,
        meta: StoreMeta,
    ) {
        lock.withLock {
            val previous = records[key]
            val nextSequence = nextSequenceOrThrow()
            val nextRecord =
                Record(
                    meta = meta,
                    lastSuccessSequence = nextSequence,
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                    staleSequence = previous?.staleSequence,
                )
            records[key] = nextRecord
            sequence = nextSequence
        }
    }

    override suspend fun recordFailure(
        key: KeyId,
        atEpochMillis: Long,
    ) {
        lock.withLock {
            val previous = records[key]
            records[key] =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = atEpochMillis,
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                    staleSequence = previous?.staleSequence,
                )
        }
    }

    override suspend fun status(key: KeyId): KeyStatus? =
        lock.withLock {
            val record = records[key]
            val coveringStaleSequence =
                maxOf(
                    record?.staleSequence ?: 0L,
                    namespaceStaleWatermarks[key.namespace] ?: 0L,
                    globalStaleWatermark,
                )
            if (record == null && coveringStaleSequence == 0L) {
                null
            } else if (record == null) {
                watermarkOnlyStatus
            } else {
                val durablyStale =
                    coveringStaleSequence > (record.lastSuccessSequence ?: 0L)
                record.cachedStatus
                    ?.takeIf { cached -> cached.durablyStale == durablyStale }
                    ?: KeyStatus(
                        meta = record.meta,
                        lastSuccessSequence = record.lastSuccessSequence,
                        lastFailureAtEpochMillis = record.lastFailureAtEpochMillis,
                        consecutiveFailures = record.consecutiveFailures,
                        durablyStale = durablyStale,
                    ).also { status ->
                        record.cachedStatus = status
                    }
            }
        }

    override suspend fun forget(key: KeyId) {
        lock.withLock {
            records.remove(key)
        }
    }

    override suspend fun markStale(key: KeyId) {
        lock.withLock {
            val previous = records[key]
            val nextSequence = nextSequenceOrThrow()
            val stagedRecords = copyRecords()
            stagedRecords[key] =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = previous?.lastFailureAtEpochMillis,
                    consecutiveFailures = previous?.consecutiveFailures ?: 0,
                    staleSequence = nextSequence,
                )
            beforeMaintenancePublishTestGate()
            records = stagedRecords
            sequence = nextSequence
        }
    }

    override suspend fun advanceStaleWatermark(namespace: String) {
        lock.withLock {
            val nextSequence = nextSequenceOrThrow()
            val stagedWatermarks =
                HashMap<String, Long>(namespaceStaleWatermarks.size).also { staged ->
                    staged.putAll(namespaceStaleWatermarks)
                    staged[namespace] = nextSequence
                }
            beforeMaintenancePublishTestGate()
            namespaceStaleWatermarks = stagedWatermarks
            sequence = nextSequence
        }
    }

    override suspend fun advanceGlobalStaleWatermark() {
        lock.withLock {
            val nextSequence = nextSequenceOrThrow()
            beforeMaintenancePublishTestGate()
            globalStaleWatermark = nextSequence
            sequence = nextSequence
        }
    }

    override suspend fun forgetNamespace(namespace: String) {
        lock.withLock {
            val stagedRecords = HashMap<KeyId, Record>(records.size)
            records.forEach { (key, record) ->
                if (key.namespace != namespace) {
                    stagedRecords[key] = record
                }
            }
            beforeMaintenancePublishTestGate()
            records = stagedRecords
        }
    }

    override suspend fun forgetAll() {
        lock.withLock {
            val stagedRecords = HashMap<KeyId, Record>()
            beforeMaintenancePublishTestGate()
            records = stagedRecords
        }
    }

    private fun copyRecords(): HashMap<KeyId, Record> =
        HashMap<KeyId, Record>(records.size).also { staged -> staged.putAll(records) }

    private fun nextSequenceOrThrow(): Long {
        check(sequence < Long.MAX_VALUE) { "Bookkeeper sequence exhausted" }
        return sequence + 1L
    }
}
