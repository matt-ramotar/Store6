@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Identifies a mutation that remains pending.
 *
 * PROVISIONAL pending Issue 021: the mutation API may change before that issue is complete.
 */
@ExperimentalStoreApi
public class PendingIntent internal constructor(
    /** The per-engine identifier assigned to the mutation. */
    @ExperimentalStoreApi
    public val mutationId: String,

    /** The identifier of the registered projection. */
    @ExperimentalStoreApi
    public val mutatorId: String,
)

/**
 * Describes a pending mutation whose projection threw.
 *
 * PROVISIONAL pending Issue 021: the mutation API may change before that issue is complete.
 */
@ExperimentalStoreApi
public class PoisonedIntent internal constructor(
    /** The per-engine identifier assigned to the mutation. */
    @ExperimentalStoreApi
    public val mutationId: String,

    /** The identifier of the registered projection that threw. */
    @ExperimentalStoreApi
    public val mutatorId: String,

    /** The exact failure thrown by the projection. */
    @ExperimentalStoreApi
    public val failure: Throwable,
)

internal class MutationEngine<K : StoreKey, V : Any>(
    private val registry: MutatorRegistry<K, V>,
    private val journal: MutationJournal<V> = InMemoryMutationJournal(),
) {
    private val mutations = Mutex()
    private var nextMutationSequence = 0L
    private val signalSink = MutableSharedFlow<StoreKey>(replay = 1)
    private val poisonSink =
        MutableSharedFlow<PoisonedIntent>(
            replay = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    internal val changes: SharedFlow<StoreKey> = signalSink.asSharedFlow()
    internal val poisoned: SharedFlow<PoisonedIntent> = poisonSink.asSharedFlow()

    internal suspend fun <A : Any> mutate(
        key: K,
        ref: MutatorRef<K, V, A>,
        args: A,
    ): String {
        require(ref.ownership === registry.ownership) {
            "MutatorRef '${ref.id}' belongs to a different MutatorRegistry."
        }
        val mutationId =
            mutations.withLock {
                nextMutationSequence += 1
                val nextId = "mutation-$nextMutationSequence"
                journal.append(
                    key.identity(),
                    JournalEntry(
                        mutationId = nextId,
                        mutatorId = ref.id,
                        args = args,
                    ),
                )
            }
        signalSink.emit(key)
        return mutationId
    }

    internal fun projectAll(
        key: K,
        base: V?,
    ): V? =
        journal.pendingSnapshot(key.identity()).fold(base) { projected, entry ->
            val projection = registry.projections[entry.mutatorId] ?: return@fold projected
            try {
                projection(projected, entry.args)
            } catch (failure: Throwable) {
                poisonSink.tryEmit(
                    PoisonedIntent(
                        mutationId = entry.mutationId,
                        mutatorId = entry.mutatorId,
                        failure = failure,
                    ),
                )
                projected
            }
        }
}
