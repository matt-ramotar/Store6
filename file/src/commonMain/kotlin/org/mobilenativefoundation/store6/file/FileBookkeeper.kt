package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.file.internal.BookkeeperFormats
import org.mobilenativefoundation.store6.file.internal.Envelope
import org.mobilenativefoundation.store6.file.internal.FileNames
import org.mobilenativefoundation.store6.file.internal.PersistedMeta
import org.mobilenativefoundation.store6.file.internal.PersistedRecord
import org.mobilenativefoundation.store6.file.internal.PersistedWatermarks
import org.mobilenativefoundation.store6.file.internal.UniqueFileNameGenerator
import org.mobilenativefoundation.store6.file.internal.atomicReplace
import org.mobilenativefoundation.store6.file.internal.ensureDirectories
import org.mobilenativefoundation.store6.file.internal.purgeRecursively
import org.mobilenativefoundation.store6.file.internal.sweepTemporaryDirectories
import kotlin.coroutines.CoroutineContext

/**
 * Filesystem-backed [Bookkeeper] with process-local status reads.
 *
 * Durable per-key records and namespace and global watermarks are written through atomic file
 * replacement. A new instance currently starts with an empty mirror and does not load previously
 * persisted state.
 *
 * [recordSuccess], [recordFailure], and [forget] update the process-local mirror first and absorb
 * non-cancellation failures from their persistence call. The maintenance operations persist first
 * and update the mirror only after persistence succeeds, so a persistence failure leaves canonical
 * disk state and the mirror unchanged.
 *
 * Only one live `FileBookkeeper` may use a directory. A [FileSourceOfTruth] may use the same
 * directory because the two classes own disjoint subtrees.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FileBookkeeper internal constructor(
    directory: Path,
    ioContext: CoroutineContext,
    private val beforeAdmissionTestGate: suspend () -> Unit = {},
    private val afterAdmissionTestGate: suspend () -> Unit = {},
    private val beforeDiskWriteTestGate: () -> Unit = {},
) : Bookkeeper {
    public constructor(
        directory: Path,
        ioContext: CoroutineContext = Dispatchers.Default,
    ) : this(
        directory = directory,
        ioContext = ioContext,
        beforeAdmissionTestGate = {},
        afterAdmissionTestGate = {},
        beforeDiskWriteTestGate = {},
    )

    private val ioContext: CoroutineContext = ioContext.minusKey(Job)
    private val bookkeepingDirectory: Path = Path(directory, "bookkeeping")
    private val recordsDirectory: Path = Path(bookkeepingDirectory, "records")
    private val watermarksPath: Path = Path(bookkeepingDirectory, "watermarks")
    private val temporaryDirectory: Path = Path(directory, "bookkeeping-tmp")
    private val trashDirectory: Path = Path(directory, "bookkeeping-trash")
    private val mutex: Mutex = Mutex()
    private val temporaryNames: UniqueFileNameGenerator = UniqueFileNameGenerator()
    private var initialized: Boolean = false
    private var records: HashMap<KeyIdentity, Record> = HashMap()
    private var namespaceStaleWatermarks: HashMap<String, Long> = HashMap()
    private var globalStaleWatermark: Long = 0L
    private var sequence: Long = 0L
    private val watermarkOnlyStatus: KeyStatus =
        KeyStatus(
            meta = null,
            lastSuccessSequence = null,
            lastFailureAtEpochMillis = null,
            consecutiveFailures = 0,
            durablyStale = true,
        )

    public override suspend fun recordSuccess(
        key: StoreKey,
        meta: StoreMeta,
    ) {
        val identity = KeyIdentity(key)
        requireValid(identity)

        admittedMutation {
            val previous = records[identity]
            val nextSequence = nextSequenceOrThrow()
            val nextRecord =
                Record(
                    meta = meta,
                    lastSuccessSequence = nextSequence,
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                    staleSequence = previous?.staleSequence,
                )
            records[identity] = nextRecord
            sequence = nextSequence
            val fileBytes = encodeRecord(nextRecord)

            try {
                withContext(ioContext) {
                    persistRecord(identity, fileBytes)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                // The process-local mirror remains authoritative while persistence is unavailable.
            }
        }
    }

    public override suspend fun recordFailure(
        key: StoreKey,
        atEpochMillis: Long,
    ) {
        val identity = KeyIdentity(key)
        requireValid(identity)

        admittedMutation {
            val previous = records[identity]
            val nextRecord =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = atEpochMillis,
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                    staleSequence = previous?.staleSequence,
                )
            records[identity] = nextRecord
            val fileBytes = encodeRecord(nextRecord)

            try {
                withContext(ioContext) {
                    persistRecord(identity, fileBytes)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                // The process-local mirror remains authoritative while persistence is unavailable.
            }
        }
    }

    public override suspend fun status(key: StoreKey): KeyStatus? {
        val identity = KeyIdentity(key)
        requireValid(identity)
        return mutex.withLock {
            if (!initialized) {
                withContext(NonCancellable) {
                    withContext(ioContext) {
                        recoverFromDiskIfNeeded()
                    }
                }
            }
            statusFromMirror(identity)
        }
    }

    public override suspend fun forget(key: StoreKey) {
        val identity = KeyIdentity(key)
        requireValid(identity)

        admittedMutation {
            records.remove(identity)
            try {
                withContext(ioContext) {
                    deleteRecord(identity)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                // The process-local mirror remains authoritative while persistence is unavailable.
            }
        }
    }

    public override suspend fun markStale(key: StoreKey) {
        val identity = KeyIdentity(key)
        requireValid(identity)

        admittedMutation {
            val previous = records[identity]
            val nextSequence = nextSequenceOrThrow()
            val nextRecord =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = previous?.lastFailureAtEpochMillis,
                    consecutiveFailures = previous?.consecutiveFailures ?: 0,
                    staleSequence = nextSequence,
                )
            val fileBytes = encodeRecord(nextRecord)

            withContext(ioContext) {
                persistRecord(identity, fileBytes)
            }
            records[identity] = nextRecord
            sequence = nextSequence
        }
    }

    public override suspend fun advanceStaleWatermark(namespace: StoreNamespace) {
        val namespaceValue = namespace.value
        FileNames.requireComponentLengths(namespaceValue, "")

        admittedMutation {
            val nextSequence = nextSequenceOrThrow()
            val nextWatermarks =
                HashMap<String, Long>(namespaceStaleWatermarks.size + 1).also { staged ->
                    staged.putAll(namespaceStaleWatermarks)
                    staged[namespaceValue] = nextSequence
                }
            val fileBytes = encodeWatermarks(nextWatermarks, globalStaleWatermark)

            withContext(ioContext) {
                persistWatermarks(fileBytes)
            }
            namespaceStaleWatermarks = nextWatermarks
            sequence = nextSequence
        }
    }

    public override suspend fun advanceGlobalStaleWatermark() {
        admittedMutation {
            val nextSequence = nextSequenceOrThrow()
            val fileBytes = encodeWatermarks(namespaceStaleWatermarks, nextSequence)

            withContext(ioContext) {
                persistWatermarks(fileBytes)
            }
            globalStaleWatermark = nextSequence
            sequence = nextSequence
        }
    }

    public override suspend fun forgetNamespace(namespace: StoreNamespace) {
        val namespaceValue = namespace.value
        FileNames.requireComponentLengths(namespaceValue, "")

        admittedMutation {
            val nextRecords = HashMap<KeyIdentity, Record>(records.size)
            records.forEach { (identity, record) ->
                if (identity.namespace != namespaceValue) {
                    nextRecords[identity] = record
                }
            }
            val trashedPath =
                withContext(ioContext) {
                    moveToTrashIfExists(
                        FileNames.namespaceDirectory(recordsDirectory, namespaceValue),
                    )
                }
            records = nextRecords
            withContext(ioContext) {
                trashedPath?.let(::purgeRecursively)
            }
        }
    }

    public override suspend fun forgetAll() {
        admittedMutation {
            val nextRecords = HashMap<KeyIdentity, Record>()
            val trashedPath =
                withContext(ioContext) {
                    moveToTrashIfExists(recordsDirectory)
                }
            records = nextRecords
            withContext(ioContext) {
                trashedPath?.let(::purgeRecursively)
            }
        }
    }

    private suspend fun <T> admittedMutation(block: suspend () -> T): T {
        beforeAdmissionTestGate()
        return mutex.withLock {
            withContext(NonCancellable) {
                afterAdmissionTestGate()
                withContext(ioContext) {
                    recoverFromDiskIfNeeded()
                }
                block()
            }
        }
    }

    private fun recoverFromDiskIfNeeded() {
        if (initialized) return
        recoverFromDisk()
        initialized = true
    }

    private fun recoverFromDisk() {
        ensureDirectories(recordsDirectory)
        sweepTemporaryDirectories(temporaryDirectory, trashDirectory)
    }

    private fun statusFromMirror(identity: KeyIdentity): KeyStatus? {
        val record = records[identity]
        val coveringStaleSequence =
            maxOf(
                record?.staleSequence ?: 0L,
                namespaceStaleWatermarks[identity.namespace] ?: 0L,
                globalStaleWatermark,
            )
        if (record == null && coveringStaleSequence == 0L) return null
        if (record == null) return watermarkOnlyStatus

        val durablyStale = coveringStaleSequence > (record.lastSuccessSequence ?: 0L)
        return record.cachedStatus
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

    private fun encodeRecord(record: Record): ByteArray {
        val payload =
            BookkeeperFormats.encodeRecord(
                PersistedRecord(
                    meta =
                        record.meta?.let { meta ->
                            PersistedMeta(
                                writtenAtEpochMillis = meta.writtenAtEpochMillis,
                                etag = meta.etag,
                            )
                        },
                    lastSuccessSequence = record.lastSuccessSequence,
                    lastFailureAtEpochMillis = record.lastFailureAtEpochMillis,
                    consecutiveFailures = record.consecutiveFailures,
                    staleSequence = record.staleSequence,
                ),
            )
        return Envelope.write(Envelope.MAGIC_RECORD, payload)
    }

    private fun encodeWatermarks(
        namespaceWatermarks: Map<String, Long>,
        globalWatermark: Long,
    ): ByteArray {
        val payload =
            BookkeeperFormats.encodeWatermarks(
                PersistedWatermarks(
                    globalStaleWatermark = globalWatermark,
                    namespaceWatermarks = namespaceWatermarks,
                ),
            )
        return Envelope.write(Envelope.MAGIC_WATERMARKS, payload)
    }

    private fun persistRecord(
        identity: KeyIdentity,
        fileBytes: ByteArray,
    ) {
        val path = pathFor(identity)
        persistFile(path, fileBytes)
    }

    private fun persistWatermarks(fileBytes: ByteArray) {
        persistFile(watermarksPath, fileBytes)
    }

    private fun persistFile(
        destination: Path,
        fileBytes: ByteArray,
    ) {
        var temporaryPath: Path? = null
        try {
            ensureDirectories(destination.parent!!)
            ensureDirectories(temporaryDirectory)
            val stagedPath = Path(temporaryDirectory, temporaryNames.nextName())
            temporaryPath = stagedPath
            beforeDiskWriteTestGate()
            SystemFileSystem.sink(stagedPath).buffered().use { sink ->
                sink.write(fileBytes)
            }
            atomicReplace(stagedPath, destination)
            temporaryPath = null
        } catch (failure: Throwable) {
            temporaryPath?.let(::bestEffortDelete)
            throw failure
        }
    }

    private fun deleteRecord(identity: KeyIdentity) {
        SystemFileSystem.delete(pathFor(identity), mustExist = false)
    }

    private fun moveToTrashIfExists(path: Path): Path? {
        if (!SystemFileSystem.exists(path)) return null
        val trashedPath = Path(trashDirectory, temporaryNames.nextName())
        atomicReplace(path, trashedPath)
        return trashedPath
    }

    private fun bestEffortDelete(path: Path) {
        try {
            SystemFileSystem.delete(path, mustExist = false)
        } catch (_: Throwable) {
            // Cleanup failure does not change the outcome of the canonical operation.
        }
    }

    private fun pathFor(identity: KeyIdentity): Path =
        FileNames.keyPath(recordsDirectory, identity.namespace, identity.canonicalId)

    private fun requireValid(identity: KeyIdentity) {
        FileNames.requireComponentLengths(identity.namespace, identity.canonicalId)
    }

    private fun nextSequenceOrThrow(): Long {
        check(sequence < Long.MAX_VALUE) { "Bookkeeper sequence exhausted" }
        return sequence + 1L
    }

    private class Record(
        val meta: StoreMeta?,
        val lastSuccessSequence: Long?,
        val lastFailureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
        val staleSequence: Long?,
        var cachedStatus: KeyStatus? = null,
    )

    private data class KeyIdentity(
        val namespace: String,
        val canonicalId: String,
    ) {
        constructor(key: StoreKey) : this(key.namespace.value, key.canonicalId())
    }
}
