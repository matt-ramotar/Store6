package org.mobilenativefoundation.store6.mutations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Lincheck lane is sharded across CI jobs, so the scenario list must be a pure function of a
 * named seed rather than of Lincheck's own generator: Lincheck 2.39 seeds `RandomProvider` with a
 * constant and exposes no seed option, so every shard would otherwise replay the same scenarios.
 */
class LincheckScenarioPlanTest {
    @Test
    fun theSameSeedProducesIdenticalScenarios() {
        assertEquals(LincheckScenarioPlan.scenarios(), LincheckScenarioPlan.scenarios())
        assertEquals(LincheckScenarioPlan.SCENARIO_COUNT, LincheckScenarioPlan.scenarios().size)
        assertEquals(
            (0 until LincheckScenarioPlan.SCENARIO_COUNT).toList(),
            LincheckScenarioPlan.scenarios().map(LincheckScenarioSpec::index),
        )
    }

    @Test
    fun everyScenarioKeepsTheThreeByThreeParallelShape() {
        LincheckScenarioPlan.scenarios().forEach { scenario ->
            assertEquals(LincheckScenarioPlan.THREAD_COUNT, scenario.threads.size)
            scenario.threads.forEach { actors ->
                assertEquals(LincheckScenarioPlan.ACTORS_PER_THREAD, actors.size)
            }
        }
    }

    @Test
    fun runOnceOperationsAppearAtMostOncePerScenario() {
        LincheckScenarioPlan.scenarios().forEach { scenario ->
            val runOnce = scenario.threads.flatten().filter(LincheckOperation::runOnce)
            assertEquals(runOnce.toSet().size, runOnce.size, "run-once repeat in scenario ${scenario.index}")
        }
    }

    @Test
    fun everyOperationIsReachableAcrossThePlan() {
        val used = LincheckScenarioPlan.scenarios().flatMap { it.threads.flatten() }.toSet()
        assertEquals(LincheckOperation.entries.toSet(), used)
    }

    @Test
    fun shardsPartitionEveryScenarioExactlyOnce() {
        for (count in 1..8) {
            val union = mutableListOf<Int>()
            for (index in 1..count) {
                val selected = LincheckScenarioPlan.indices(LincheckShard(index, count))
                assertTrue(selected.isNotEmpty(), "empty shard $index/$count")
                assertEquals(selected.sorted(), selected)
                union += selected
            }
            assertEquals((0 until LincheckScenarioPlan.SCENARIO_COUNT).toList(), union.sorted())
            assertEquals(union.size, union.toSet().size, "overlapping shards for N=$count")
        }
    }

    @Test
    fun shardScenariosFollowTheShardIndices() {
        val shard = LincheckShard(2, 4)
        assertEquals(
            LincheckScenarioPlan.indices(shard),
            LincheckScenarioPlan.scenarios(shard).map(LincheckScenarioSpec::index),
        )
        assertEquals(25, LincheckScenarioPlan.scenarios(shard).size)
    }

    @Test
    fun anAbsentShardSpecificationSelectsEveryScenario() {
        val shard = LincheckScenarioPlan.shard(null)
        assertEquals(LincheckShard(1, 1), shard)
        assertEquals(LincheckScenarioPlan.scenarios(), LincheckScenarioPlan.scenarios(shard))
    }

    @Test
    fun wellFormedShardSpecificationsParse() {
        assertEquals(LincheckShard(1, 4), LincheckScenarioPlan.shard("1/4"))
        assertEquals(LincheckShard(4, 4), LincheckScenarioPlan.shard(" 4/4 "))
        assertEquals(LincheckShard(100, 100), LincheckScenarioPlan.shard("100/100"))
    }

    @Test
    fun malformedOrOutOfRangeShardSpecificationsAreRejected() {
        val rejected = listOf("", "   ", "1", "4", "0/4", "5/4", "-1/4", "1/0", "1/-4", "1/101",
                              "1/4/2", "a/b", "1 / 4", "1,4", "one/four", "1.0/4")
        rejected.forEach { specification ->
            val failure = assertFailsWith<IllegalArgumentException>("accepted '$specification'") {
                LincheckScenarioPlan.shard(specification)
            }
            assertTrue(
                LincheckScenarioPlan.SHARD_PROPERTY in failure.message.orEmpty() &&
                    "k/N" in failure.message.orEmpty(),
                "message must name the expected form: ${failure.message}",
            )
        }
    }

    @Test
    fun theScenarioMarkerNamesTheShardAndItsIndices() {
        val shard = LincheckShard(1, 4)
        val marker = LincheckScenarioPlan.marker(shard, LincheckScenarioPlan.indices(shard))
        assertEquals(
            "${LincheckScenarioPlan.SCENARIO_MARKER} shard=1/4 count=25 " +
                "indices=" + (0 until 100).filter { it % 4 == 0 }.joinToString(","),
            marker,
        )
    }
}
