package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException

/** Stable diagnostic used when an operation is attempted after store closure. */
internal const val STORE_CLOSED_MESSAGE: String = "Store is closed."

/** Creates the deterministic failure for an operation that requires an open store. */
internal fun storeClosedException(): IllegalStateException =
    IllegalStateException(STORE_CLOSED_MESSAGE)

/** Creates the cancellation used to terminate work that was active when the store closed. */
internal fun storeClosedCancellation(): CancellationException =
    CancellationException(STORE_CLOSED_MESSAGE)
