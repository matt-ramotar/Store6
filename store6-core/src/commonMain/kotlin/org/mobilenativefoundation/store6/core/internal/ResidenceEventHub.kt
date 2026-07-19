package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.channels.Channel

/** One lossless residence transition published to every active stream collector. */
internal sealed interface ResidenceEvent<out V : Any> {
    /** Residence became absent after a clear or server-side deletion. */
    data object Absent : ResidenceEvent<Nothing>

    /** Residence committed or revalidated [value]. */
    class Value<V : Any>(val value: V) : ResidenceEvent<V>
}

/**
 * Broadcasts residence transitions without StateFlow conflation.
 *
 * All methods are non-suspending and are called while the owning KeyEngine holds its state lock,
 * making registration atomic with the collector's initial state/residence snapshot. Each active
 * collector owns an unlimited channel so a slow collector retains every clear/value cycle; the
 * channel and its queued events are released when that collector unregisters.
 */
internal class ResidenceEventHub<V : Any> {
    private val observers = mutableSetOf<Channel<ResidenceEvent<V>>>()

    fun register(): Channel<ResidenceEvent<V>> =
        Channel<ResidenceEvent<V>>(capacity = Channel.UNLIMITED).also(observers::add)

    fun unregister(observer: Channel<ResidenceEvent<V>>) {
        observers.remove(observer)
        observer.close()
    }

    fun publish(event: ResidenceEvent<V>) {
        observers.removeAll { observer -> observer.trySend(event).isFailure }
    }
}
