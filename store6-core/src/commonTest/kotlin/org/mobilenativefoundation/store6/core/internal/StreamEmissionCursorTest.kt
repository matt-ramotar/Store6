package org.mobilenativefoundation.store6.core.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamEmissionCursorTest {
    @Test
    fun immediateSuccess_startsWithExplicitRefreshingStaleThenFreshValue() {
        val stale = Token("stale")
        val fresh = Token("fresh")
        val cursor = refreshingCursor(stale)

        val initial = assertNotNull(cursor.initialEmission())
        assertEquals(stale, initial.value)
        assertEquals(true, initial.refreshingOverride)

        val next = cursor.observe(fresh)
        assertEquals(1, next.size)
        val freshSignal = assertIs<StreamEmission.Value<Token>>(next.single())
        assertEquals(fresh, freshSignal.value)
        assertEquals(null, freshSignal.refreshingOverride)
    }

    @Test
    fun immediateFailure_startsWithExplicitRefreshingStaleAndSuppressesItsReplay() {
        val stale = Token("stale")
        val cursor = refreshingCursor(stale)

        val initial = assertNotNull(cursor.initialEmission())
        assertEquals(stale, initial.value)
        assertEquals(true, initial.refreshingOverride)

        // The outcome watcher starts only after this explicit initial signal is enqueued.
    }

    @Test
    fun immediateNotModified_startsWithExplicitRefreshingStaleThenFreshCopy() {
        val stale = Token("stale")
        val revalidated = stale.copy()
        val cursor = refreshingCursor(stale)

        val initial = assertNotNull(cursor.initialEmission())
        assertEquals(stale, initial.value)
        assertEquals(true, initial.refreshingOverride)

        val next = cursor.observe(revalidated)
        assertEquals(1, next.size)
        val revalidatedSignal = assertIs<StreamEmission.Value<Token>>(next.single())
        assertTrue(revalidatedSignal.value !== stale)
        assertEquals(revalidated, revalidatedSignal.value)
    }

    @Test
    fun withheldInitial_suppressesUnservableStateFlowReplay() {
        val withheld = Token("withheld")
        val fresh = Token("fresh")
        val cursor =
            StreamEmissionCursor(
                initial = withheld,
                servesInitial = false,
                refreshReserved = true,
            )

        assertEquals(null, cursor.initialEmission())
        assertEquals(
            fresh,
            assertIs<StreamEmission.Value<Token>>(
                cursor.observe(fresh).single(),
            ).value,
        )
    }

    @Test
    fun clearAndImmediateReplacement_preservesLoadingBeforeReplacementValue() {
        val resident = Token("resident")
        val replacement = Token("replacement")
        val cursor = servedCursor(resident)
        assertNotNull(cursor.initialEmission())

        val signals = cursor.observe(value = null) + cursor.observe(replacement)

        assertEquals(2, signals.size)
        assertIs<StreamEmission.Loading>(signals[0])
        assertEquals(
            replacement,
            assertIs<StreamEmission.Value<Token>>(signals[1]).value,
        )
    }

    @Test
    fun observedNullThenReplacement_emitsExactlyOneLoadingForTheClearEpoch() {
        val resident = Token("resident")
        val replacement = Token("replacement")
        val cursor = servedCursor(resident)
        assertNotNull(cursor.initialEmission())

        val absent = cursor.observe(value = null)
        assertEquals(1, absent.size)
        assertIs<StreamEmission.Loading>(absent.single())

        val restored = cursor.observe(replacement)
        assertEquals(1, restored.size)
        assertEquals(
            replacement,
            assertIs<StreamEmission.Value<Token>>(restored.single()).value,
        )
    }

    @Test
    fun twoClearAndReplacementCycles_preserveEveryTransition() {
        val resident = Token("resident")
        val replacement1 = Token("replacement-1")
        val replacement2 = Token("replacement-2")
        val cursor = servedCursor(resident)
        assertNotNull(cursor.initialEmission())

        val signals =
            cursor.observe(null) +
                cursor.observe(replacement1) +
                cursor.observe(null) +
                cursor.observe(replacement2)

        assertEquals(4, signals.size)
        assertIs<StreamEmission.Loading>(signals[0])
        assertEquals(replacement1, assertIs<StreamEmission.Value<Token>>(signals[1]).value)
        assertIs<StreamEmission.Loading>(signals[2])
        assertEquals(replacement2, assertIs<StreamEmission.Value<Token>>(signals[3]).value)
    }

    private fun refreshingCursor(initial: Token): StreamEmissionCursor<Token> =
        StreamEmissionCursor(
            initial = initial,
            servesInitial = true,
            refreshReserved = true,
        )

    private fun servedCursor(initial: Token): StreamEmissionCursor<Token> =
        StreamEmissionCursor(
            initial = initial,
            servesInitial = true,
            refreshReserved = false,
        )

    private data class Token(val label: String)
}
