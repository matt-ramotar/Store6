package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.StoreResult

/**
 * Decouples Store result production from collection while retaining every lifecycle signal.
 *
 * The pending queue contains at most one Data value per run of consecutive Data values. Lifecycle
 * signals split runs and remain lossless, so queue growth is attributable to those signals rather
 * than to an independently unbounded Data buffer.
 */
internal fun <V> Flow<StoreResult<V>>.conflateLatestData(): Flow<StoreResult<V>> = flow {
    coroutineScope {
        val pending = ArrayDeque<StoreResult<V>>()
        val mutex = Mutex()
        val wakeVersion = MutableStateFlow(0L)
        var upstreamComplete = false
        var upstreamFailure: Throwable? = null

        val upstream = launch {
            try {
                this@conflateLatestData.collect { result ->
                    mutex.withLock {
                        if (
                            result is StoreResult.Data<*> &&
                            pending.lastOrNull() is StoreResult.Data<*>
                        ) {
                            pending.removeLast()
                        }
                        pending.addLast(result)
                        wakeVersion.value += 1
                    }
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                mutex.withLock { upstreamFailure = failure }
            } finally {
                mutex.withLock {
                    upstreamComplete = true
                    wakeVersion.value += 1
                }
            }
        }

        try {
            while (true) {
                var next: StoreResult<V>? = null
                var complete = false
                var failure: Throwable? = null
                var observedWakeVersion = 0L

                mutex.withLock {
                    if (pending.isNotEmpty()) {
                        next = pending.removeFirst()
                    } else if (upstreamComplete) {
                        complete = true
                        failure = upstreamFailure
                    } else {
                        observedWakeVersion = wakeVersion.value
                    }
                }

                when {
                    next != null -> emit(next!!)
                    complete -> {
                        failure?.let { throw it }
                        break
                    }
                    else -> wakeVersion.first { it > observedWakeVersion }
                }
            }
        } finally {
            upstream.cancel()
        }
    }
}
