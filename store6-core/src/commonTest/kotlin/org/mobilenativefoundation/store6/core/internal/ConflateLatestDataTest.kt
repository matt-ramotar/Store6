package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class ConflateLatestDataTest {

    @Test
    fun slowCollector_getsLatestDataAndEveryLifecycleSignalBeforeCompletion() = runTest {
        val firstDataSeen = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val upstreamCompleted = CompletableDeferred<Unit>()
        val received = mutableListOf<StoreResult<Int>>()

        val upstream = flow<StoreResult<Int>> {
            emit(data(1))
            firstDataSeen.await()
            emit(data(2))
            emit(data(3))
            emit(StoreResult.Loading())
            emit(StoreResult.Loading())
            emit(StoreResult.Revalidated(age = Duration.ZERO))
            emit(
                StoreResult.Error(
                    error = StoreError.Fetch(message = "fetch failed", cause = null),
                    servedStale = true,
                ),
            )
            upstreamCompleted.complete(Unit)
        }

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.conflateLatestData().collect { result ->
                received += result
                if (result is StoreResult.Data && result.value == 1) {
                    firstDataSeen.complete(Unit)
                    releaseCollector.await()
                }
            }
        }

        firstDataSeen.await()
        upstreamCompleted.await()
        assertTrue(collector.isActive)

        releaseCollector.complete(Unit)
        collector.join()

        assertEquals(
            listOf("data:1", "data:3", "loading", "loading", "revalidated", "error"),
            received.map(::label),
        )
    }

    @Test
    fun immediateCollector_getsEverySynchronousDataEmission() = runTest {
        val received = mutableListOf<StoreResult<Int>>()

        val upstream = flow<StoreResult<Int>> {
            emit(data(1))
            emit(data(2))
            emit(data(3))
        }

        upstream.conflateLatestData().collect { result ->
            received += result
        }

        assertEquals(
            listOf("data:1", "data:2", "data:3"),
            received.map(::label),
        )
    }

    @Test
    fun eachCollector_hasIndependentConflationState() = runTest {
        var subscriptions = 0
        val upstream = flow<StoreResult<Int>> {
            subscriptions += 1
            emit(data(subscriptions))
        }
        val first = mutableListOf<StoreResult<Int>>()
        val second = mutableListOf<StoreResult<Int>>()
        val conflated = upstream.conflateLatestData()

        conflated.collect(first::add)
        conflated.collect(second::add)

        assertEquals(listOf("data:1"), first.map(::label))
        assertEquals(listOf("data:2"), second.map(::label))
    }

    @Test
    fun cancellingCollector_cancelsUpstream() = runTest {
        val upstreamStarted = CompletableDeferred<Unit>()
        val upstreamCancelled = CompletableDeferred<Unit>()
        val upstream = flow<StoreResult<Int>> {
            upstreamStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                upstreamCancelled.complete(Unit)
            }
        }

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.conflateLatestData().collect()
        }
        upstreamStarted.await()

        collector.cancelAndJoin()

        upstreamCancelled.await()
    }

    private fun data(value: Int): StoreResult.Data<Int> =
        StoreResult.Data(
            value = value,
            origin = Origin.MEMORY,
            age = Duration.ZERO,
            isStale = false,
            refreshing = false,
        )

    private fun label(result: StoreResult<Int>): String =
        when (result) {
            is StoreResult.Data -> "data:${result.value}"
            is StoreResult.Loading -> "loading"
            is StoreResult.Revalidated -> "revalidated"
            is StoreResult.Error -> "error"
        }
}
