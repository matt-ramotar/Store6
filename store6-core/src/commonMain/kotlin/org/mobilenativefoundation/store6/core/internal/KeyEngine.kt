package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.FetcherResult
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult
import kotlin.time.TimeSource

/** Emits every stale epoch that advanced beyond the snapshot used to plan a stream startup. */
internal fun Flow<KeyState>.staleEpochsAfter(planningEpoch: Long): Flow<Long> =
    map { it.staleEpoch }
        .distinctUntilChanged()
        .filter { observedEpoch -> observedEpoch > planningEpoch }

/** Returns whether resident data satisfies the current invalidation epoch. */
internal fun residenceSatisfies(
    state: KeyState,
    residentStaleEpochAtCommit: Long?,
): Boolean =
    residentStaleEpochAtCommit != null && residentStaleEpochAtCommit >= state.staleEpoch

/**
 * Coordinates residence, fetch ownership, and invalidation epochs for one canonical key.
 *
 * Immutable [KeyState] snapshots change only under [stateLock] by applying the pure
 * [transition] function. Residence is written only inside the same critical section as the
 * state change that authorizes it (commit and clear), which keeps state and residence coherent;
 * a StateFlow assignment is a memory write, not I/O, so this preserves the rule that
 * [stateLock] is never held across I/O. Fetches run in [engineScope] and therefore continue
 * when an individual waiter cancels. Staleness is level-triggered monotone state: streams react
 * to [KeyState.staleEpoch] changes, so a burst of invalidations conflates losslessly.
 */
internal class KeyEngine<K : StoreKey, V : Any>(
    private val key: K,
    private val keyId: KeyId,
    private val fetcher: suspend (K) -> FetcherResult<V>,
    private val bookkeeper: Bookkeeper,
    private val validator: FreshnessValidator,
    private val wallClock: WallClock,
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
     * Returns the active ticket, launching a fetch only when the state grants ownership, or
     * `null` when residence became satisfying before this function acquired [stateLock].
     *
     * The open-state check, residence recheck, and ticket creation share the state critical
     * section. This makes the recheck the linearization point between a concurrent commit and
     * an Idle-to-InFlight transition, and the open check the linearization point between a
     * concurrent fetch request and store closure.
     */
    private suspend fun ensureFetch(): FetchTicket? {
        val effect =
            stateLock.withLock {
                ensureOpen()
                val snapshot = mutableState.value
                if (residenceSatisfies(snapshot, residence.value?.staleEpochAtCommit)) {
                    return@withLock null
                }
                val fresh = FetchTicket(CompletableDeferred<FetchOutcome>(engineJob))
                val result = transition(snapshot, KeyEvent.EnsureFetch(fresh))
                mutableState.value = result.state
                result.effect
            }

        return when (effect) {
            null -> null
            is KeyEffect.Launch -> effect.ticket.also(::launchFetch)
            is KeyEffect.Join -> effect.ticket
            else -> error("Ensure-fetch transition produced an invalid effect: $effect")
        }
    }

    /**
     * Runs the owned fetch and publishes its terminal outcome to every joined waiter.
     *
     * Undispatched start enters the guarded coroutine body before this function returns, so
     * cancellation cleanup is installed when closure races immediately after ticket creation.
     */
    private fun launchFetch(ticket: FetchTicket) {
        val fetchJob = engineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val outcome =
                try {
                    currentCoroutineContext().ensureActive()
                    yield()
                    val result = fetcher(key)
                    currentCoroutineContext().ensureActive()
                    when (result) {
                        is FetcherResult.Success -> commitFetch(ticket, result.value)
                        is FetcherResult.Error -> {
                            if (result.cause is CancellationException) {
                                throw result.cause
                            }
                            FetchOutcome.Failed(fetchException(result.cause))
                        }
                        is FetcherResult.NotModified,
                        FetcherResult.Deleted,
                        ->
                            FetchOutcome.Failed(
                                fetchException(
                                    IllegalStateException(
                                        "Rich fetcher results are honored by the T6 engine rework.",
                                    ),
                                ),
                            )
                    }
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

    /**
     * Commits the fetched value unless a clear advanced the key's clear epoch after launch.
     *
     * The residence assignment shares the state critical section with the commit decision so
     * the pair can never be observed torn, and a successful commit settles the fetch slot in
     * the same transition (see [transition]). The envelope is stamped with the commit-time
     * stale epoch: an invalidation that lands mid-flight is satisfied by this commit, matching
     * the engine design's commit-time sequencing.
     */
    private suspend fun commitFetch(
        ticket: FetchTicket,
        value: V,
    ): FetchOutcome =
        stateLock.withLock {
            val result = transition(mutableState.value, KeyEvent.CommitFetch(ticket))
            mutableState.value = result.state
            when (result.effect) {
                KeyEffect.Commit -> {
                    residence.value = ValueEnvelope(
                        value = value,
                        origin = Origin.FETCHER,
                        committedAt = TimeSource.Monotonic.markNow(),
                        staleEpochAtCommit = result.state.staleEpoch,
                    )
                    FetchOutcome.Committed
                }

                KeyEffect.Superseded -> FetchOutcome.Superseded
                else -> error("Commit-fetch transition produced an invalid effect: ${result.effect}")
            }
        }

    /** Settles fetch ownership even when the owning coroutine has already been cancelled. */
    private suspend fun settleFetch(ticket: FetchTicket) {
        withContext(NonCancellable) {
            applyEvent(KeyEvent.SettleFetch(ticket))
        }
    }

    /** Atomically settles ownership, classifies clear supersession, and completes the ticket. */
    private suspend fun finishFetch(
        ticket: FetchTicket,
        outcome: FetchOutcome,
    ) {
        withContext(NonCancellable) {
            stateLock.withLock {
                val result = transition(mutableState.value, KeyEvent.SettleFetch(ticket))
                mutableState.value = result.state
                val classifiedOutcome =
                    when (result.effect) {
                        KeyEffect.Superseded -> FetchOutcome.Superseded
                        KeyEffect.Settled,
                        KeyEffect.Ignored,
                        -> outcome

                        else -> error(
                            "Settle-fetch transition produced an invalid effect: ${result.effect}",
                        )
                    }
                if (engineJob.isActive) {
                    ticket.outcome.complete(classifiedOutcome)
                } else {
                    ticket.outcome.cancel(storeClosedCancellation())
                }
            }
        }
    }

    /** Marks this key stale; resident streams observe the epoch change and re-plan. */
    internal suspend fun invalidate() {
        applyEvent(KeyEvent.Invalidate)
    }

    /**
     * Destructively removes this key's resident value.
     *
     * The epoch bumps and the residence removal share one critical section, so an in-flight
     * commit observes the new clear epoch (and is superseded) or completed entirely before the
     * clear (and its value is removed here) — there is no in-between.
     */
    internal suspend fun clear() {
        stateLock.withLock {
            val result = transition(mutableState.value, KeyEvent.Clear)
            mutableState.value = result.state
            check(result.effect == KeyEffect.ClearResidence) {
                "Clear transition produced an invalid effect: ${result.effect}"
            }
            residence.value = null
        }
    }

    /** Creates the structured exception shared by stream and value-returning operations. */
    private fun fetchException(failure: Throwable): StoreException {
        val message =
            "Fetch failed for key '${keyId.namespace}/${keyId.canonicalId}': ${failure.message}. " +
                "The fetcher threw; inspect the cause for the underlying failure."
        return StoreException(
            error = StoreError.Fetch(message = message, cause = failure),
            cause = failure,
        )
    }

    /** Creates the typed failure for a fetch whose commit was superseded by a concurrent clear. */
    private fun supersededException(): StoreException {
        val message =
            "Could not return a value for key '${keyId.namespace}/${keyId.canonicalId}': clear() " +
                "removed the key while its fetch was in flight, so the fetched value was " +
                "discarded. The value is currently missing; retry the read to trigger a " +
                "fresh fetch."
        return StoreException(
            error = StoreError.Missing(key = key, message = message),
        )
    }

    /**
     * Merges fetch outcomes with replaying resident data for a live result stream.
     *
     * Initial plan: an absent resident value emits Loading and fetches; a stale resident value
     * fetches without Loading (the stale value is served by replay). ensureFetch runs inline
     * before residence collection starts so the replayed stale emission deterministically
     * reports refreshing = true. Invalidation is observed as level-triggered state — the stale
     * epoch collector re-plans on every change and can never miss a signal.
     */
    internal fun stream(freshness: Freshness): Flow<StoreResult<V>> {
        ensureOpen()
        return channelFlow {
            ensureOpen()
            val producer = this
            val closeHandle =
                closeSignal.invokeOnCompletion {
                    producer.cancel(storeClosedCancellation())
                }

            try {
                val initial = residence.value
                val planningEpoch = state.value.staleEpoch
                if (initial == null) {
                    send(StoreResult.Loading())
                }
                if (initial == null || initial.staleEpochAtCommit < planningEpoch) {
                    ensureFetch()?.let { ticket ->
                        launch { watchOutcome(ticket) }
                    }
                }

                launch {
                    state.staleEpochsAfter(planningEpoch).collect {
                        refetchWhileUnsatisfied()
                    }
                }

                var sawValue = initial != null
                residence.collect { envelope ->
                    if (envelope != null) {
                        sawValue = true
                        send(toData(envelope))
                    } else if (sawValue) {
                        sawValue = false
                        send(StoreResult.Loading())
                    }
                }
            } finally {
                closeHandle.dispose()
            }
        }
    }

    /** Computes an honest Data emission from the envelope and the current state snapshot. */
    private fun toData(envelope: ValueEnvelope<V>): StoreResult.Data<V> {
        val snapshot = state.value
        return StoreResult.Data(
            value = envelope.value,
            origin = envelope.origin,
            age = envelope.committedAt.elapsedNow(),
            isStale = envelope.staleEpochAtCommit < snapshot.staleEpoch,
            refreshing = snapshot.fetch is FetchSlot.InFlight,
        )
    }

    /** Surfaces one fetch outcome into the stream; a superseded commit re-plans. */
    private suspend fun ProducerScope<StoreResult<V>>.watchOutcome(ticket: FetchTicket) {
        when (val outcome = ticket.outcome.await()) {
            FetchOutcome.Committed -> Unit
            is FetchOutcome.Failed ->
                send(StoreResult.Error(outcome.exception.error, servedStale = false))

            FetchOutcome.Superseded -> refetchWhileUnsatisfied()
        }
    }

    /**
     * Fetches until the resident value satisfies the current stale epoch.
     *
     * The loop re-plans after a superseded outcome because a post-clear ensureFetch may join
     * the doomed pre-clear ticket; iteration is bounded by the number of concurrent clears.
     */
    private suspend fun ProducerScope<StoreResult<V>>.refetchWhileUnsatisfied() {
        while (true) {
            val envelope = residence.value
            if (envelope != null && envelope.staleEpochAtCommit >= state.value.staleEpoch) {
                return
            }
            val ticket = ensureFetch() ?: return
            when (val outcome = ticket.outcome.await()) {
                FetchOutcome.Committed -> return
                is FetchOutcome.Failed -> {
                    send(StoreResult.Error(outcome.exception.error, servedStale = false))
                    return
                }

                FetchOutcome.Superseded -> Unit
            }
        }
    }

    /**
     * Returns resident data immediately or joins the single active fetch for this key.
     *
     * A stale resident value is served after firing a background refetch (stale-while-
     * revalidate). A committed outcome whose residence was cleared before this waiter could
     * read it reports Missing, exactly like a superseded commit.
     */
    internal suspend fun get(freshness: Freshness): V {
        ensureOpen()
        residence.value?.let { envelope ->
            if (envelope.staleEpochAtCommit < state.value.staleEpoch) {
                ensureFetch()
            }
            return envelope.value
        }

        val ticket = ensureFetch()
        if (ticket == null) {
            return residence.value?.value ?: throw supersededException()
        }

        return when (val outcome = ticket.outcome.await()) {
            FetchOutcome.Committed ->
                residence.value?.value ?: throw supersededException()

            is FetchOutcome.Failed -> throw outcome.exception
            FetchOutcome.Superseded -> throw supersededException()
        }
    }

    /** Fails deterministically when this engine's store scope is no longer active. */
    private fun ensureOpen() {
        if (!engineJob.isActive) {
            throw storeClosedException()
        }
    }
}
