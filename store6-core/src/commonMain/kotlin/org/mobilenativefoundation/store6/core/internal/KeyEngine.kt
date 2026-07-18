package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult

/**
 * Coordinates residence and fetch ownership for one canonical key.
 *
 * Immutable [KeyState] snapshots change only under [stateLock] by applying the pure
 * [transition] function. The active fetch owns the only residence write path and commits its
 * value before completing the shared [FetchTicket], so all joined waiters observe committed
 * data. Fetches run in [engineScope] and therefore continue when an individual waiter cancels.
 * Operations that also serialize writes must acquire `writeLock` before [stateLock]; reverse
 * acquisition is forbidden, and [stateLock] must never be held across I/O.
 */
internal class KeyEngine<K : StoreKey, V : Any>(
    private val key: K,
    private val fetcher: suspend (K) -> V,
    private val engineScope: CoroutineScope,
) {
    private val stateLock = Mutex()
    private val engineJob: Job = checkNotNull(engineScope.coroutineContext[Job])

    /** Child job that completes as soon as the engine begins cancellation. */
    private val closeSignal: Job = Job(engineJob)

    private val mutableState = MutableStateFlow(KeyState.Initial)

    /** Read-only observation surface for immutable fetch-state snapshots. */
    internal val state: StateFlow<KeyState> = mutableState.asStateFlow()

    private val residence = MutableStateFlow<ValueEnvelope<V>?>(null)

    /** Applies one event while serializing the state swap, then returns its effect. */
    private suspend fun applyEvent(event: KeyEvent): KeyEffect =
        stateLock.withLock {
            val result = transition(mutableState.value, event)
            mutableState.value = result.state
            result.effect
        }

    /**
     * Returns the active ticket, launching a fetch only when the state grants ownership.
     *
     * The open-state check and ticket creation share the state critical section, making that
     * check the linearization point between a concurrent fetch request and store closure.
     */
    private suspend fun ensureFetch(): FetchTicket {
        val effect =
            stateLock.withLock {
                ensureOpen()
                val fresh = FetchTicket(CompletableDeferred<FetchOutcome>(engineJob))
                val result = transition(mutableState.value, KeyEvent.EnsureFetch(fresh))
                mutableState.value = result.state
                result.effect
            }

        return when (effect) {
            is KeyEffect.Launch -> effect.ticket.also(::launchFetch)
            is KeyEffect.Join -> effect.ticket
            KeyEffect.Ignored,
            KeyEffect.Settled,
            -> error("Ensure-fetch transition produced an invalid effect: $effect")
        }
    }

    /**
     * Runs the owned fetch and publishes its terminal outcome to every joined waiter.
     *
     * Dispatching through [engineScope] keeps a fetcher's synchronous work off the calling thread.
     * The ticket's parent job and the completion handler give waiters a terminal result when
     * cancellation prevents the coroutine body from starting or completing normally.
     */
    private fun launchFetch(ticket: FetchTicket) {
        val fetchJob = engineScope.launch {
            val outcome =
                try {
                    currentCoroutineContext().ensureActive()
                    val value = fetcher(key)
                    currentCoroutineContext().ensureActive()
                    residence.value = ValueEnvelope(value, Origin.FETCHER)
                    FetchOutcome.Committed
                } catch (cancellation: CancellationException) {
                    ticket.outcome.cancel(cancellation)
                    settleFetch(ticket)
                    throw cancellation
                } catch (failure: Throwable) {
                    FetchOutcome.Failed(fetchException(failure))
                }

            finishFetch(ticket, outcome)
        }

        fetchJob.invokeOnCompletion { failure ->
            if (failure != null) {
                ticket.outcome.cancel(storeClosedCancellation())
            }
        }
    }

    /** Settles fetch ownership even when the owning coroutine has already been cancelled. */
    private suspend fun settleFetch(ticket: FetchTicket) {
        withContext(NonCancellable) {
            applyEvent(KeyEvent.SettleFetch(ticket))
        }
    }

    /** Atomically settles ownership and gives every waiter a terminal ticket outcome. */
    private suspend fun finishFetch(
        ticket: FetchTicket,
        outcome: FetchOutcome,
    ) {
        withContext(NonCancellable) {
            applyEvent(KeyEvent.SettleFetch(ticket))
            if (engineJob.isActive) {
                ticket.outcome.complete(outcome)
            } else {
                ticket.outcome.cancel(storeClosedCancellation())
            }
        }
    }

    /** Creates the structured exception shared by stream and value-returning operations. */
    private fun fetchException(failure: Throwable): StoreException {
        val id = KeyId.from(key)
        val message =
            "Fetch failed for key '${id.namespace}/${id.canonicalId}': ${failure.message}. " +
                "The fetcher threw; inspect the cause for the underlying failure."
        return StoreException(
            error = StoreError.Fetch(message = message, cause = failure),
            cause = failure,
        )
    }

    /**
     * Merges the one-shot fetch outcome with replaying resident data for a live result stream.
     *
     * If a commit occurs between the initial residence read and the fetch-state transition, a
     * collector can initiate one redundant fetch after the earlier fetch settles. The race does
     * not violate single-flight ownership or change the validity and origin of emitted data.
     */
    internal fun stream(): Flow<StoreResult<V>> {
        ensureOpen()
        return channelFlow {
            ensureOpen()
            val producer = this
            val closeHandle =
                closeSignal.invokeOnCompletion {
                    producer.cancel(storeClosedCancellation())
                }

            try {
                if (residence.value == null) {
                    send(StoreResult.Loading())
                    val ticket = ensureFetch()
                    launch { watchOutcome(ticket) }
                }

                residence.collect { envelope ->
                    if (envelope != null) {
                        send(StoreResult.Data(envelope.value, envelope.origin))
                    }
                }
            } finally {
                closeHandle.dispose()
            }
        }
    }

    /** Emits a failed fetch outcome while leaving the surrounding result stream active. */
    private suspend fun ProducerScope<StoreResult<V>>.watchOutcome(ticket: FetchTicket) {
        when (val outcome = ticket.outcome.await()) {
            FetchOutcome.Committed -> Unit
            is FetchOutcome.Failed -> send(StoreResult.Error(outcome.exception.error))
        }
    }

    /** Returns resident data immediately or joins the single active fetch for this key. */
    internal suspend fun get(): V {
        ensureOpen()
        residence.value?.let { return it.value }

        return when (val outcome = ensureFetch().outcome.await()) {
            FetchOutcome.Committed -> checkNotNull(residence.value).value
            is FetchOutcome.Failed -> throw outcome.exception
        }
    }

    /** Fails deterministically when this engine's store scope is no longer active. */
    private fun ensureOpen() {
        if (!engineJob.isActive) {
            throw storeClosedException()
        }
    }
}
