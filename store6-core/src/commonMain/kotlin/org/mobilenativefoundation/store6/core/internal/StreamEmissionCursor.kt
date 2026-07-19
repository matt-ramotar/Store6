package org.mobilenativefoundation.store6.core.internal

/** One residence-derived signal that a stream must publish in observation order. */
internal sealed interface StreamEmission<out V : Any> {
    /** The previously observed residence became absent. */
    data object Loading : StreamEmission<Nothing>

    /** A resident value to publish, optionally overriding its live refreshing snapshot. */
    class Value<V : Any>(
        val value: V,
        val refreshingOverride: Boolean? = null,
    ) : StreamEmission<V>
}

/**
 * Preserves non-conflatable stream semantics on top of a conflating residence StateFlow.
 *
 * The captured initial value is emitted explicitly before an outcome watcher can publish. Its
 * Later residence transitions arrive through the lossless [ResidenceEventHub], so this cursor
 * only has to preserve whether this collector has observed a value and translate each ordered
 * value/absent event into its public signal.
 */
internal class StreamEmissionCursor<V : Any>(
    private val initial: V?,
    private val servesInitial: Boolean,
    private val refreshReserved: Boolean,
) {
    private var sawValue: Boolean = servesInitial && initial != null

    /** Returns the captured served value that must precede all later residence/outcome signals. */
    fun initialEmission(): StreamEmission.Value<V>? =
        if (servesInitial && initial != null) {
            StreamEmission.Value(initial, refreshingOverride = refreshReserved)
        } else {
            null
        }

    /** Converts one ordered residence event into the public signals owed to a collector. */
    fun observe(value: V?): List<StreamEmission<V>> {
        if (value == null) {
            if (!sawValue) return emptyList()
            sawValue = false
            return listOf(StreamEmission.Loading)
        }

        sawValue = true
        return listOf(StreamEmission.Value(value))
    }
}
