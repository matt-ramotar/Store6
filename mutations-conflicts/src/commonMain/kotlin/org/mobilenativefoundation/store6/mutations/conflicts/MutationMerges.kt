@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationConflictResolution
import org.mobilenativefoundation.store6.mutations.MutationPresence

/** Factories for merge policies accepted by the mutation conflict builder. */
@ExperimentalStoreApi
public object MutationMerges {

    /**
     * Selects server-wins for every combination of base, local, and authoritative presence.
     *
     * This is behaviorally identical to registering no merge. It exists so the choice is explicit
     * in code review, pairs with a caller-supplied `precondition { }`, and names the default.
     */
    @ExperimentalStoreApi
    public fun <V : Any> serverWins(): MutationMergeFunction<V> =
        { _, _, _ -> MutationConflictResolution.ServerWins }

    /**
     * Reasserts the same local presence after every conflict, including `Retry(Absent)` for a
     * deletion.
     *
     * A retry converges when the next generation's precondition — built from the post-barrier
     * recapture — matches server state at the next push. When the barrier fetch adopts the
     * server's conflicting value, that is the common case. When it does not, or the server keeps
     * moving, the unchanged-conflict bound parks the intent after three consecutive conflicted
     * generations with an identical server timestamp/etag pair. A server minting fresh serverMeta
     * on every rejection never trips the bound, so client-wins retries indefinitely at drain
     * cadence against such a server.
     */
    @ExperimentalStoreApi
    public fun <V : Any> clientWins(): MutationMergeFunction<V> =
        { _, mine, _ -> MutationConflictResolution.Retry(mine) }

    /**
     * The mutator's projector must assign a fresh stamp to every local write. A projector copying
     * the base's stamp produces ties, and ties lose to the server, so the policy silently degrades
     * to server-wins.
     *
     * Decision table (P = present, A = absent):
     * - mine P, theirs P: `writtenAt(mine) > writtenAt(theirs)` resolves `Retry(mine)`; otherwise
     *   `ServerWins`. Ties go to the server.
     * - mine A, theirs P: [onMineAbsent] — `THEIRS` resolves `ServerWins`; `MINE` resolves
     *   `Retry(Absent)`, re-pushing the deletion.
     * - mine P, theirs A: [onTheirsAbsent] — `THEIRS` resolves `ServerWins`, staying deleted;
     *   `MINE` resolves `Retry(mine)`, recreating the entity.
     * - mine A, theirs A: `ServerWins`; nothing to contend.
     *
     * `base` never participates. A bare `lastWriteWins { ... }` call is last-write-wins when both
     * sides are present and server-wins otherwise. Wall-clock stamps are skew-sensitive; prefer
     * server-assigned or hybrid stamps. A [writtenAt] function that throws parks the intent with
     * kind `CONFLICT` and detail `"merge-failed"`.
     */
    @ExperimentalStoreApi
    public fun <V : Any> lastWriteWins(
        onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        writtenAt: (V) -> Long,
    ): MutationMergeFunction<V> =
        { _, mine, theirs ->
            when (mine) {
                is MutationPresence.Present ->
                    when (theirs) {
                        is MutationPresence.Present ->
                            if (writtenAt(mine.value) > writtenAt(theirs.value)) {
                                MutationConflictResolution.Retry(mine)
                            } else {
                                MutationConflictResolution.ServerWins
                            }

                        MutationPresence.Absent ->
                            when (onTheirsAbsent) {
                                MutationConflictBias.THEIRS ->
                                    MutationConflictResolution.ServerWins

                                MutationConflictBias.MINE ->
                                    MutationConflictResolution.Retry(mine)
                            }
                    }

                MutationPresence.Absent ->
                    when (theirs) {
                        is MutationPresence.Present ->
                            when (onMineAbsent) {
                                MutationConflictBias.THEIRS ->
                                    MutationConflictResolution.ServerWins

                                MutationConflictBias.MINE ->
                                    MutationConflictResolution.Retry(mine)
                            }

                        MutationPresence.Absent -> MutationConflictResolution.ServerWins
                    }
            }
        }
}
