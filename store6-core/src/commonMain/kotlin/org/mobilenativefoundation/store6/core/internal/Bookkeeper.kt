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
 * Mutation methods are operationally infallible: implementations absorb or report their own
 * storage failures and do not throw them through this interface. Cooperative cancellation may
 * still propagate as `CancellationException`; implementations must not suppress it.
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
}

/** Immutable bookkeeping state for one canonical key. */
internal class KeyStatus(
    val meta: StoreMeta?,
    val lastSuccessSequence: Long?,
    val lastFailureAtEpochMillis: Long?,
    val consecutiveFailures: Int,
)

/** In-memory [Bookkeeper] with a store-global success sequence. */
internal class InMemoryBookkeeper : Bookkeeper {
    private val lock = Mutex()
    private val statuses = HashMap<KeyId, KeyStatus>()
    private var successSequence = 0L

    override suspend fun recordSuccess(
        key: KeyId,
        meta: StoreMeta,
    ) {
        lock.withLock {
            successSequence += 1L
            statuses[key] =
                KeyStatus(
                    meta = meta,
                    lastSuccessSequence = successSequence,
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                )
        }
    }

    override suspend fun recordFailure(
        key: KeyId,
        atEpochMillis: Long,
    ) {
        lock.withLock {
            val previous = statuses[key]
            statuses[key] =
                KeyStatus(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = atEpochMillis,
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                )
        }
    }

    override suspend fun status(key: KeyId): KeyStatus? =
        lock.withLock { statuses[key] }

    override suspend fun forget(key: KeyId) {
        lock.withLock {
            statuses.remove(key)
        }
    }
}
