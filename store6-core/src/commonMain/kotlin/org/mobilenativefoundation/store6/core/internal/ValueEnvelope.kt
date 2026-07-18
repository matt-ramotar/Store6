package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Origin

/** An immutable resident value paired with the source that produced it. */
internal data class ValueEnvelope<V : Any>(
    val value: V,
    val origin: Origin,
)
