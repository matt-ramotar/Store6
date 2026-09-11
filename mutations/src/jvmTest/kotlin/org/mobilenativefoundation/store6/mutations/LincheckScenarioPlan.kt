package org.mobilenativefoundation.store6.mutations

import kotlin.random.Random

/**
 * The `@Operation`s of [MutationJournalLincheckTest], named so that a scenario plan can be built,
 * sharded and printed without touching Lincheck's own generator.
 *
 * [runOnce] mirrors `@Operation(runOnce = true)`: the sequential specification allocates a slot
 * once, so a repeated append or retire would diverge from the implementation for a reason that has
 * nothing to do with linearizability.
 */
internal enum class LincheckOperation(val runOnce: Boolean) {
    APPEND_A(runOnce = true),
    APPEND_B(runOnce = true),
    RETIRE_A(runOnce = true),
    RETIRE_B(runOnce = true),
    CONFIRM_TWO(runOnce = true),
    PRUNE(runOnce = false),
    HYDRATE(runOnce = false),
}

/** One shard of the plan: [index] is 1-based and at most [count]. */
internal data class LincheckShard(val index: Int, val count: Int) {
    override fun toString(): String = "$index/$count"
}

/** A single parallel scenario: [LincheckScenarioPlan.THREAD_COUNT] threads of equal length. */
internal data class LincheckScenarioSpec(
    val index: Int,
    val threads: List<List<LincheckOperation>>,
)

/**
 * The deterministic scenario plan for the Lincheck lane.
 *
 * Lincheck 2.39 seeds its `RandomProvider` with a constant `0L` and exposes no seed option, so
 * shards cut by giving each job fewer `iterations` would replay the same scenarios in every shard.
 * The plan is therefore generated here from [SCENARIO_SEED] and registered with
 * `Options.addCustomScenario`, with `iterations(0)` suppressing Lincheck's own random scenarios.
 *
 * Scenario `i` belongs to shard `k` of `N` exactly when `i % N == k - 1`, so the shards partition
 * `0 until SCENARIO_COUNT` no matter how many jobs the release owner runs.
 */
internal object LincheckScenarioPlan {
    /** Changing this reshuffles every scenario; it is part of the release evidence. */
    const val SCENARIO_SEED: Long = 20260911L
    const val SCENARIO_COUNT: Int = 100
    const val THREAD_COUNT: Int = 3
    const val ACTORS_PER_THREAD: Int = 3

    /** JVM system property carrying `k/N`; absent means the whole plan. */
    const val SHARD_PROPERTY: String = "store6.lincheckShard"

    /** Log token the release evidence recorder parses the executed indices from. */
    const val SCENARIO_MARKER: String = "store6-lincheck-scenarios"

    /** The shard that selects every scenario, used when [SHARD_PROPERTY] is absent. */
    val WHOLE_PLAN: LincheckShard = LincheckShard(index = 1, count = 1)

    private val PLAN: List<LincheckScenarioSpec> by lazy {
        val random = Random(SCENARIO_SEED)
        List(SCENARIO_COUNT) { index -> LincheckScenarioSpec(index, threads(random)) }
    }

    fun scenarios(): List<LincheckScenarioSpec> = PLAN

    fun scenarios(shard: LincheckShard): List<LincheckScenarioSpec> =
        indices(shard).map { index -> PLAN[index] }

    fun indices(shard: LincheckShard): List<Int> =
        (0 until SCENARIO_COUNT).filter { index -> index % shard.count == shard.index - 1 }

    /** Parses `k/N`. `null` selects the whole plan; anything else malformed fails fast. */
    fun shard(specification: String?): LincheckShard {
        if (specification == null) return WHOLE_PLAN
        val match = SHARD_FORM.matchEntire(specification.trim())
        val index = match?.groupValues?.get(1)?.toIntOrNull()
        val count = match?.groupValues?.get(2)?.toIntOrNull()
        require(index != null && count != null && count in 1..SCENARIO_COUNT && index in 1..count) {
            "$SHARD_PROPERTY must be k/N with 1 <= k <= N <= $SCENARIO_COUNT (1-based shard index), " +
                "for example 1/4; got '$specification'"
        }
        return LincheckShard(index, count)
    }

    fun marker(
        shard: LincheckShard,
        indices: List<Int>,
    ): String = "$SCENARIO_MARKER shard=$shard count=${indices.size} indices=${indices.joinToString(",")}"

    private fun threads(random: Random): List<List<LincheckOperation>> {
        val allowed = LincheckOperation.entries.toMutableList()
        return List(THREAD_COUNT) {
            List(ACTORS_PER_THREAD) {
                val operation = allowed[random.nextInt(allowed.size)]
                if (operation.runOnce) allowed.remove(operation)
                operation
            }
        }
    }

    private val SHARD_FORM = Regex("""(\d+)/(\d+)""")
}
