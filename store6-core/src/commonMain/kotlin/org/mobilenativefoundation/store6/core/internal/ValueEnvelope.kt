package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreMeta

/** An immutable resident value paired with the provenance needed for honest emissions. */
internal data class ValueEnvelope<V : Any>(
    val value: V,
    val origin: Origin,

    /** Typed freshness metadata recorded at commit; age derives from its written-at instant. */
    val meta: StoreMeta,

    /** The key's stale epoch at commit; the value is epoch-stale when the current epoch is greater. */
    val staleEpochAtCommit: Long,
)
