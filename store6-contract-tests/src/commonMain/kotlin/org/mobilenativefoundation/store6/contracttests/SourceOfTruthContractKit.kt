@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.contracttests

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reusable contract tests for [SourceOfTruth] implementations.
 *
 * Every shipped or reusable adapter and fake must have a CI-executed subclass of this kit.
 * Purpose-built gated or fault-injection fakes are excluded because they intentionally violate a
 * contract edge in order to exercise engine recovery.
 *
 * @param K the key type accepted by the implementation
 * @param V the non-null value type stored by the implementation
 */
public abstract class SourceOfTruthContractKit<K : StoreKey, V : Any> {
    /** Creates a fresh source of truth for one contract test. */
    public abstract fun createSourceOfTruth(): SourceOfTruth<K, V>

    /** Returns the first stable test key. */
    public abstract val keyA: K

    /** Returns a stable test key distinct from [keyA]. */
    public abstract val keyB: K

    /** Returns a stable value for [index], distinct from values returned for other indices. */
    public abstract fun value(index: Int): V

    @Test
    public fun readerStaysLiveAcrossDelete(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val first = value(1)
        val second = value(2)
        sourceOfTruth.write(keyA, first)

        sourceOfTruth.reader(keyA).test {
            assertEquals(first, awaitItem())

            sourceOfTruth.delete(keyA)
            assertNull(awaitItem())

            sourceOfTruth.write(keyA, second)
            assertEquals(second, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun readerFirstEmissionIsCurrentValue(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)
        sourceOfTruth.write(keyA, current)

        sourceOfTruth.reader(keyA).test {
            assertEquals(current, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun readerFirstEmissionIsNullWhenAbsent(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun deleteEmitsNull(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)
        sourceOfTruth.write(keyA, current)

        sourceOfTruth.reader(keyA).test {
            assertEquals(current, awaitItem())
            sourceOfTruth.delete(keyA)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun readerNeverCompletesNormally(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun readerStaysLiveAcrossThreeDeleteCycles(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            repeat(3) { index ->
                val current = value(index)
                sourceOfTruth.write(keyA, current)
                assertEquals(current, awaitItem())
                sourceOfTruth.delete(keyA)
                assertNull(awaitItem())
            }
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun equalValueRewriteEmits(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            sourceOfTruth.write(keyA, current)
            assertEquals(current, awaitItem())
            sourceOfTruth.write(keyA, current)
            assertEquals(current, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun writeIsVisibleToLateReader(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)
        sourceOfTruth.write(keyA, current)

        sourceOfTruth.reader(keyA).test {
            assertEquals(current, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun deleteIsVisibleToLateReader(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        sourceOfTruth.write(keyA, value(1))
        sourceOfTruth.delete(keyA)

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun keysAreIsolated(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)

        sourceOfTruth.reader(keyB).test {
            assertNull(awaitItem())
            sourceOfTruth.write(keyA, current)
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        sourceOfTruth.reader(keyA).test {
            assertEquals(current, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun twoConcurrentReadersBothSeeWrite(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)

        turbineScope {
            val first = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            val second = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            try {
                assertNull(first.awaitItem())
                assertNull(second.awaitItem())
                sourceOfTruth.write(keyA, current)
                assertEquals(current, first.awaitItem())
                assertEquals(current, second.awaitItem())
            } finally {
                first.cancelAndIgnoreRemainingEvents()
                second.cancelAndIgnoreRemainingEvents()
            }
        }
    }
}
