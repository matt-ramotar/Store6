package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResidenceEventHubTest {
    @Test
    fun slowObserver_retainsTwoCompleteClearAndReplacementCycles() = runTest {
        val hub = ResidenceEventHub<Token>()
        val observer = hub.register()
        val replacement1 = Token("replacement-1")
        val replacement2 = Token("replacement-2")

        hub.publish(ResidenceEvent.Absent)
        hub.publish(ResidenceEvent.Value(replacement1))
        hub.publish(ResidenceEvent.Absent)
        hub.publish(ResidenceEvent.Value(replacement2))

        assertIs<ResidenceEvent.Absent>(observer.receive())
        assertEquals(replacement1, assertIs<ResidenceEvent.Value<Token>>(observer.receive()).value)
        assertIs<ResidenceEvent.Absent>(observer.receive())
        assertEquals(replacement2, assertIs<ResidenceEvent.Value<Token>>(observer.receive()).value)
        hub.unregister(observer)
    }

    @Test
    fun everyActiveObserver_receivesEveryEventInOrder() = runTest {
        val hub = ResidenceEventHub<Token>()
        val first = hub.register()
        val second = hub.register()
        val value = Token("value")

        hub.publish(ResidenceEvent.Absent)
        hub.publish(ResidenceEvent.Value(value))

        for (observer in listOf(first, second)) {
            assertIs<ResidenceEvent.Absent>(observer.receive())
            assertEquals(value, assertIs<ResidenceEvent.Value<Token>>(observer.receive()).value)
        }

        hub.unregister(first)
        hub.unregister(second)
        assertTrue(first.receiveCatching().isClosed)
        assertTrue(second.receiveCatching().isClosed)
    }

    private data class Token(val label: String)
}
