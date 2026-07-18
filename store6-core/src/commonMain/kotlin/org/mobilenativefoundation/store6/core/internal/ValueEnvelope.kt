package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Origin
import kotlin.time.TimeSource

/** An immutable resident value paired with the provenance needed for honest emissions. */
internal data class ValueEnvelope<V : Any>(
    val value: V,
    val origin: Origin,

    /** Monotonic mark taken at commit; age is computed as elapsed time from this mark. */
    val committedAt: TimeSource.Monotonic.ValueTimeMark,

    /** The key's stale epoch at commit; the value is stale when the current epoch is greater. */
    val staleEpochAtCommit: Long,
)
