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

/** In-memory [Bookkeeper] with one store-local sequence for successes, marks, and watermarks. */
internal class InMemoryBookkeeper : Bookkeeper {
    private class Record(
        val meta: StoreMeta?,
        val lastSuccessSequence: Long?,
        val lastFailureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
        val staleSequence: Long?,
        var cachedStatus: KeyStatus? = null,
    )

    private val lock = Mutex()
    private val records = HashMap<KeyId, Record>()
    private val namespaceStaleWatermarks = HashMap<String, Long>()
    private var globalStaleWatermark = 0L
    private var sequence = 0L

    override suspend fun recordSuccess(
        key: KeyId,
        meta: StoreMeta,
    ) {
        lock.withLock {
            val previous = records[key]
            records[key] =
                Record(
                    meta = meta,
                    lastSuccessSequence = nextSequence(),
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                    staleSequence = previous?.staleSequence,
                )
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
            } else {
                val durablyStale =
                    coveringStaleSequence > (record?.lastSuccessSequence ?: 0L)
                record?.cachedStatus
                    ?.takeIf { cached -> cached.durablyStale == durablyStale }
                    ?: KeyStatus(
                        meta = record?.meta,
                        lastSuccessSequence = record?.lastSuccessSequence,
                        lastFailureAtEpochMillis = record?.lastFailureAtEpochMillis,
                        consecutiveFailures = record?.consecutiveFailures ?: 0,
                        durablyStale = durablyStale,
                    ).also { status ->
                        if (record != null) record.cachedStatus = status
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
            records[key] =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = previous?.lastFailureAtEpochMillis,
                    consecutiveFailures = previous?.consecutiveFailures ?: 0,
                    staleSequence = nextSequence(),
                )
        }
    }

    override suspend fun advanceStaleWatermark(namespace: String) {
        lock.withLock {
            namespaceStaleWatermarks[namespace] = nextSequence()
        }
    }

    override suspend fun advanceGlobalStaleWatermark() {
        lock.withLock {
            globalStaleWatermark = nextSequence()
        }
    }

    override suspend fun forgetNamespace(namespace: String) {
        lock.withLock {
            records.keys.removeAll { key -> key.namespace == namespace }
        }
    }

    override suspend fun forgetAll() {
        lock.withLock {
            records.clear()
        }
    }

    private fun nextSequence(): Long {
        sequence += 1L
        return sequence
    }
}
