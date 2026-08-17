@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.mutations.MutationConflictBuilder

/**
 * Registers [MutationMerges.serverWins] via [MutationConflictBuilder.merge].
 *
 * This function never registers a precondition selector, so it composes with a caller-supplied
 * `precondition { }` in the same conflict block.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.serverWins() {
    merge(MutationMerges.serverWins())
}

/**
 * Registers [MutationMerges.clientWins] via [MutationConflictBuilder.merge].
 *
 * This function never registers a precondition selector, so it composes with a caller-supplied
 * `precondition { }` in the same conflict block.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.clientWins() {
    merge(MutationMerges.clientWins())
}

/**
 * Registers [MutationMerges.lastWriteWins] via [MutationConflictBuilder.merge].
 *
 * This function never registers a precondition selector, so it composes with a caller-supplied
 * `precondition { }` in the same conflict block.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.lastWriteWins(
    onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    writtenAt: (V) -> Long,
) {
    merge(
        MutationMerges.lastWriteWins(
            onMineAbsent = onMineAbsent,
            onTheirsAbsent = onTheirsAbsent,
            writtenAt = writtenAt,
        ),
    )
}
