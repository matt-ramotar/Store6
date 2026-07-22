package org.mobilenativefoundation.store6.sqldelight.internal

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * Runs a suspend block to synchronous completion inside a SQLDelight transaction without
 * dispatching or `runBlocking`. The unintercepted coroutine intrinsic deterministically reports
 * the first genuine suspension. If that happens, this throws and the enclosing transaction rolls
 * back; later resumption of the orphaned block is a programming error.
 */
internal fun <R> runNonSuspending(block: suspend () -> R): R {
    val result =
        block.startCoroutineUninterceptedOrReturn(
            Continuation(EmptyCoroutineContext) { outcome -> outcome.getOrThrow() },
        )
    if (result === COROUTINE_SUSPENDED) {
        throw IllegalStateException(
            "withTransaction block suspended while executing against a synchronous SQLDelight " +
                "driver for this store. Keep the block to synchronous same-database statements " +
                "(this adapter's write/delete and other SQLDelight statements); move asynchronous " +
                "work outside withTransaction.",
        )
    }
    @Suppress("UNCHECKED_CAST")
    return result as R
}
