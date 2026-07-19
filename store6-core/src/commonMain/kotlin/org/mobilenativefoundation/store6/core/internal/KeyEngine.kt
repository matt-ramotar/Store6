package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreResult
import kotlin.time.Duration.Companion.milliseconds

/** Emits every stale epoch that advanced beyond the snapshot used to plan a stream startup. */
internal fun Flow<KeyState>.staleEpochsAfter(planningEpoch: Long): Flow<Long> =
    map { it.staleEpoch }
        .distinctUntilChanged()
        .filter { observedEpoch -> observedEpoch > planningEpoch }

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

    /** Orders metadata mutations; when both locks are needed, this is acquired before stateLock. */
    private val bookkeepingLock = Mutex()
    private val engineJob: Job = checkNotNull(engineScope.coroutineContext[Job])

    /** Child job that completes as soon as the engine begins cancellation. */
    private val closeSignal: Job = Job(engineJob)

    private val mutableState = MutableStateFlow(KeyState.Initial)

    /** Read-only observation surface for immutable fetch-state snapshots. */
    internal val state: StateFlow<KeyState> = mutableState.asStateFlow()

    private val residence = MutableStateFlow<ValueEnvelope<V>?>(null)
    private val residenceEvents = ResidenceEventHub<ValueEnvelope<V>>()

    /** Updates residence and publishes its lossless stream event while stateLock is held. */
    private fun updateResidence(envelope: ValueEnvelope<V>?) {
        residence.value = envelope
        residenceEvents.publish(
            if (envelope == null) ResidenceEvent.Absent else ResidenceEvent.Value(envelope),
        )
    }

    /** Applies one event while serializing the state swap, then returns its effect. */
    private suspend fun applyEvent(event: KeyEvent): KeyEffect =
        stateLock.withLock {
            val result = transition(mutableState.value, event)
            mutableState.value = result.state
            result.effect
        }

    /** Plans one read against a coherent state snapshot and resident envelope. */
    private fun planFor(
        freshness: Freshness,
        snapshot: KeyState,
        envelope: ValueEnvelope<V>?,
        nowEpochMillis: Long,
    ): FetchPlan =
        validator.plan(
            FreshnessContext(
                hasResidentValue = envelope != null,
                meta = envelope?.meta,
                epochStale = envelope != null && envelope.staleEpochAtCommit < snapshot.staleEpoch,
                freshness = freshness,
                nowEpochMillis = nowEpochMillis,
                status = null, // Present behavior: durable status feeds planning in 006
            ),
        )

    /** Plans using the latest state and the injected wall clock. */
    private fun currentPlan(
        freshness: Freshness,
        envelope: ValueEnvelope<V>?,
    ): FetchPlan =
        planFor(freshness, state.value, envelope, wallClock.nowEpochMillis())

    /** Whether this policy serves an invalidated resident while a refresh is attempted. */
    private fun staleServingTolerated(freshness: Freshness): Boolean =
        freshness == Freshness.CachedOrFetch || freshness == Freshness.StaleIfError

    /**
     * Returns the active ticket, launching a fetch only when the state grants ownership, or
     * `null` when the current policy plan is already satisfied.
     *
     * The wall-clock read happens before [stateLock], and the plan recheck is the linearization
     * point between a concurrent commit and an Idle-to-InFlight transition.
     */
    private suspend fun ensureFetch(freshness: Freshness): FetchTicket? {
        val now = wallClock.nowEpochMillis()
        val effect =
            stateLock.withLock {
                ensureOpen()
                val snapshot = mutableState.value
                if (planFor(freshness, snapshot, residence.value, now) is FetchPlan.Skip) {
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
                        is FetcherResult.Success -> commitFetch(ticket, result.value, result.etag)
                        is FetcherResult.NotModified -> commitNotModified(ticket, result.etag)
                        is FetcherResult.Error -> {
                            if (result.cause is CancellationException) {
                                throw result.cause
                            }
                            FetchOutcome.Failed(
                                exception = fetchResultException(result.cause),
                                atEpochMillis = wallClock.nowEpochMillis(),
                            )
                        }

                        FetcherResult.Deleted -> commitDeleted(ticket)
                    }
                } catch (cancellation: CancellationException) {
                    ticket.outcome.cancel(cancellation)
                    settleFetch(ticket)
                    throw cancellation
                } catch (failure: Throwable) {
                    FetchOutcome.Failed(
                        exception = fetchException(failure),
                        atEpochMillis = wallClock.nowEpochMillis(),
                    )
                }

            finishFetch(ticket, outcome)
        }

        fetchJob.invokeOnCompletion { failure ->
            if (failure != null) {
                ticket.outcome.cancel(storeClosedCancellation())
            }
        }
    }

    /** Commits fetched data unless a clear superseded this ticket. */
    private suspend fun commitFetch(
        ticket: FetchTicket,
        value: V,
        etag: String?,
    ): FetchOutcome {
        val meta = EngineStoreMeta(writtenAtEpochMillis = wallClock.nowEpochMillis(), etag = etag)
        return bookkeepingLock.withLock {
            val outcome =
                stateLock.withLock {
                    val result = transition(mutableState.value, KeyEvent.CommitFetch(ticket))
                    mutableState.value = result.state
                    when (result.effect) {
                        KeyEffect.Commit -> {
                            updateResidence(
                                ValueEnvelope(
                                    value = value,
                                    origin = Origin.FETCHER,
                                    meta = meta,
                                    staleEpochAtCommit = result.state.staleEpoch,
                                ),
                            )
                            FetchOutcome.Committed
                        }

                        KeyEffect.Superseded -> FetchOutcome.Superseded
                        else -> error(
                            "Commit-fetch transition produced an invalid effect: ${result.effect}",
                        )
                    }
                }
            if (outcome == FetchOutcome.Committed) {
                bookkeeper.recordSuccess(keyId, meta)
            }
            outcome
        }
    }

    /** Refreshes resident metadata in place for a not-modified response. */
    private suspend fun commitNotModified(
        ticket: FetchTicket,
        etag: String?,
    ): FetchOutcome {
        val now = wallClock.nowEpochMillis()
        return bookkeepingLock.withLock {
            var refreshedMeta: StoreMeta? = null
            val outcome =
                stateLock.withLock {
                    val result = transition(mutableState.value, KeyEvent.CommitFetch(ticket))
                    mutableState.value = result.state
                    when (result.effect) {
                        KeyEffect.Commit -> {
                            residence.value?.let { current ->
                                val meta = EngineStoreMeta(now, etag ?: current.meta.etag)
                                refreshedMeta = meta
                                updateResidence(
                                    current.copy(
                                        meta = meta,
                                        staleEpochAtCommit = result.state.staleEpoch,
                                    ),
                                )
                            }
                            FetchOutcome.Revalidated
                        }

                        KeyEffect.Superseded -> FetchOutcome.Superseded
                        else -> error(
                            "Commit-fetch transition produced an invalid effect: ${result.effect}",
                        )
                    }
                }
            refreshedMeta?.let { bookkeeper.recordSuccess(keyId, it) }
            outcome
        }
    }

    /** Removes residence and metadata for a server-side deletion. */
    private suspend fun commitDeleted(ticket: FetchTicket): FetchOutcome =
        bookkeepingLock.withLock {
            val outcome =
                stateLock.withLock {
                    val result = transition(mutableState.value, KeyEvent.CommitDeleted(ticket))
                    mutableState.value = result.state
                    when (result.effect) {
                        KeyEffect.CommitDelete -> {
                            updateResidence(null)
                            FetchOutcome.Deleted
                        }

                        KeyEffect.Superseded -> FetchOutcome.Superseded
                        else -> error(
                            "Commit-deleted transition produced an invalid effect: ${result.effect}",
                        )
                    }
                }
            if (outcome == FetchOutcome.Deleted) {
                bookkeeper.forget(keyId)
            }
            outcome
        }

    /** Settles fetch ownership even when the owning coroutine has already been cancelled. */
    private suspend fun settleFetch(ticket: FetchTicket) {
        withContext(NonCancellable) {
            applyEvent(KeyEvent.SettleFetch(ticket))
        }
    }

    /** Settles ownership, preserving ordered failure bookkeeping without blocking engine close. */
    private suspend fun finishFetch(
        ticket: FetchTicket,
        outcome: FetchOutcome,
    ) {
        if (outcome is FetchOutcome.Failed) {
            finishFailedFetch(ticket, outcome)
        } else {
            withContext(NonCancellable) {
                stateLock.withLock {
                    completeTicket(ticket, classifySettledOutcome(ticket, outcome))
                }
            }
        }
    }

    /** Orders a failure record with clear/delete while leaving Bookkeeper suspension cancellable. */
    private suspend fun finishFailedFetch(
        ticket: FetchTicket,
        outcome: FetchOutcome.Failed,
    ) {
        try {
            bookkeepingLock.withLock {
                val classifiedOutcome =
                    stateLock.withLock {
                        classifySettledOutcome(ticket, outcome)
                    }
                if (classifiedOutcome is FetchOutcome.Failed) {
                    bookkeeper.recordFailure(keyId, classifiedOutcome.atEpochMillis)
                }
                completeTicket(ticket, classifiedOutcome)
            }
        } catch (cancellation: CancellationException) {
            ticket.outcome.cancel(cancellation)
            settleFetch(ticket)
            throw cancellation
        }
    }

    /** Applies the settle transition while the caller holds stateLock. */
    private fun classifySettledOutcome(
        ticket: FetchTicket,
        outcome: FetchOutcome,
    ): FetchOutcome {
        val result = transition(mutableState.value, KeyEvent.SettleFetch(ticket))
        mutableState.value = result.state
        return when (result.effect) {
            KeyEffect.Superseded -> FetchOutcome.Superseded
            KeyEffect.Settled,
            KeyEffect.Ignored,
            -> outcome

            else -> error(
                "Settle-fetch transition produced an invalid effect: ${result.effect}",
            )
        }
    }

    /** Completes or cancels a ticket after its state and bookkeeping are settled. */
    private fun completeTicket(
        ticket: FetchTicket,
        outcome: FetchOutcome,
    ) {
        if (engineJob.isActive) {
            ticket.outcome.complete(outcome)
        } else {
            ticket.outcome.cancel(storeClosedCancellation())
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
        bookkeepingLock.withLock {
            stateLock.withLock {
                val result = transition(mutableState.value, KeyEvent.Clear)
                mutableState.value = result.state
                check(result.effect == KeyEffect.ClearResidence) {
                    "Clear transition produced an invalid effect: ${result.effect}"
                }
                updateResidence(null)
            }
            bookkeeper.forget(keyId)
        }
    }

    /** Creates the structured exception for a thrown fetcher failure. */
    private fun fetchException(failure: Throwable): StoreException {
        val message =
            "Fetch failed for key '${keyId.namespace}/${keyId.canonicalId}': ${failure.message}. " +
                "The fetcher threw; inspect the cause for the underlying failure."
        return StoreException(
            error = StoreError.Fetch(message = message, cause = failure),
            cause = failure,
        )
    }

    /** Creates the structured exception for an explicit rich-fetcher failure result. */
    private fun fetchResultException(failure: Throwable): StoreException {
        val message =
            "Fetch failed for key '${keyId.namespace}/${keyId.canonicalId}': ${failure.message}. " +
                "The fetcher returned FetcherResult.Error; inspect the cause for the " +
                "underlying failure."
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

    /** Creates the typed failure for a server-side deletion. */
    private fun serverDeletedException(): StoreException {
        val message =
            "Could not return a value for key '${keyId.namespace}/${keyId.canonicalId}': the " +
                "fetcher reported that the server deleted this value, so the local copy was " +
                "removed. Recreate the value upstream or treat Missing as the empty state."
        return StoreException(
            error = StoreError.Missing(key = key, message = message),
        )
    }

    /** Creates the typed failure for an absent local-only read. */
    private fun localOnlyMissingException(): StoreException {
        val message =
            "Could not return a value for key '${keyId.namespace}/${keyId.canonicalId}': " +
                "Freshness.LocalOnly forbids fetching and no local value exists. Seed the key " +
                "with another policy first or handle StoreError.Missing as the empty state."
        return StoreException(
            error = StoreError.Missing(key = key, message = message),
        )
    }

    /** Creates the typed failure for a not-modified response without a resident value. */
    private fun notModifiedWithoutValueException(): StoreException {
        val message =
            "Could not return a value for key '${keyId.namespace}/${keyId.canonicalId}': the " +
                "fetcher returned FetcherResult.NotModified but no local value exists to " +
                "revalidate. Return FetcherResult.Success with a full value when the client " +
                "has no cached copy."
        return StoreException(
            error = StoreError.Missing(key = key, message = message),
        )
    }

    /**
     * Merges one sequential fetch-outcome watcher with replaying resident data.
     *
     * The MustBeFresh initial cycle is awaited inline so its failures are terminal before any
     * watcher can launch. Later invalidations are observed as level-triggered epoch changes.
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
            var registeredObserver: Channel<ResidenceEvent<ValueEnvelope<V>>>? = null

            try {
                val planningNow = wallClock.nowEpochMillis()
                val (planningState, initial, observer) =
                    stateLock.withLock {
                        ensureOpen()
                        Triple(
                            mutableState.value,
                            residence.value,
                            residenceEvents.register(),
                        )
                    }
                registeredObserver = observer
                var planningEpoch = planningState.staleEpoch
                val plan = planFor(freshness, planningState, initial, planningNow)
                val serveInitial = initial != null && plan.servesResident

                if (freshness == Freshness.LocalOnly && initial == null) {
                    send(StoreResult.Error(localOnlyMissingException().error, servedStale = false))
                } else if (!serveInitial) {
                    send(StoreResult.Loading())
                }

                var initialTicket: FetchTicket? = null
                if (plan !is FetchPlan.Skip) {
                    initialTicket = ensureFetch(freshness)
                }

                if (freshness == Freshness.MustBeFresh && initialTicket != null) {
                    val terminalError: StoreError? =
                        when (val outcome = initialTicket.outcome.await()) {
                            FetchOutcome.Committed -> null
                            FetchOutcome.Revalidated ->
                                if (residence.value == null) {
                                    notModifiedWithoutValueException().error
                                } else {
                                    null
                                }

                            is FetchOutcome.Failed -> outcome.exception.error
                            FetchOutcome.Deleted -> serverDeletedException().error
                            FetchOutcome.Superseded -> supersededException().error
                        }
                    if (terminalError != null) {
                        send(StoreResult.Error(terminalError, servedStale = false))
                        close()
                        return@channelFlow
                    }
                    val committedEpoch =
                        stateLock.withLock {
                            residence.value?.staleEpochAtCommit
                        }
                    planningEpoch = maxOf(planningEpoch, committedEpoch ?: planningEpoch)
                    initialTicket = null
                }

                val emissionCursor =
                    StreamEmissionCursor(
                        initial = initial,
                        servesInitial = serveInitial,
                        refreshReserved =
                            plan !is FetchPlan.Skip || planningState.fetch is FetchSlot.InFlight,
                    )
                emissionCursor.initialEmission()?.let { emission ->
                    send(
                        toData(
                            envelope = emission.value,
                            freshness = freshness,
                            refreshingOverride = emission.refreshingOverride,
                        ),
                    )
                }

                launch {
                    initialTicket?.let {
                        watchOutcome(it, freshness, servedResident = serveInitial)
                    }
                    state.staleEpochsAfter(planningEpoch).collect {
                        refetchWhileUnsatisfied(freshness)
                    }
                }

                for (event in observer) {
                    val observedResidence =
                        when (event) {
                            ResidenceEvent.Absent -> null
                            is ResidenceEvent.Value -> event.value
                        }
                    emissionCursor.observe(observedResidence).forEach { emission ->
                        when (emission) {
                            StreamEmission.Loading -> send(StoreResult.Loading())
                            is StreamEmission.Value ->
                                send(
                                    toData(
                                        envelope = emission.value,
                                        freshness = freshness,
                                        refreshingOverride = emission.refreshingOverride,
                                    ),
                                )
                        }
                    }
                }
            } finally {
                registeredObserver?.let { observer ->
                    withContext(NonCancellable) {
                        stateLock.withLock {
                            residenceEvents.unregister(observer)
                        }
                    }
                }
                closeHandle.dispose()
            }
        }
    }

    /** Surfaces one fetch outcome into the stream; a superseded commit re-plans. */
    private suspend fun ProducerScope<StoreResult<V>>.watchOutcome(
        ticket: FetchTicket,
        freshness: Freshness,
        servedResident: Boolean,
    ) {
        when (val outcome = ticket.outcome.await()) {
            FetchOutcome.Committed -> Unit
            FetchOutcome.Revalidated ->
                if (residence.value == null) {
                    send(
                        StoreResult.Error(
                            notModifiedWithoutValueException().error,
                            servedStale = false,
                        ),
                    )
                }

            FetchOutcome.Deleted ->
                send(StoreResult.Error(serverDeletedException().error, servedStale = false))

            is FetchOutcome.Failed ->
                send(
                    StoreResult.Error(
                        outcome.exception.error,
                        servedStale = servedResident && staleServingTolerated(freshness),
                    ),
                )

            FetchOutcome.Superseded -> refetchWhileUnsatisfied(freshness)
        }
    }

    /** Fetches until the current freshness plan is satisfied. */
    private suspend fun ProducerScope<StoreResult<V>>.refetchWhileUnsatisfied(
        freshness: Freshness,
    ) {
        while (true) {
            val servedResident =
                residence.value != null && staleServingTolerated(freshness)
            val ticket = ensureFetch(freshness) ?: return
            when (val outcome = ticket.outcome.await()) {
                FetchOutcome.Committed -> return
                FetchOutcome.Revalidated -> {
                    if (residence.value == null) {
                        send(
                            StoreResult.Error(
                                notModifiedWithoutValueException().error,
                                servedStale = false,
                            ),
                        )
                    }
                    return
                }

                FetchOutcome.Deleted -> {
                    send(StoreResult.Error(serverDeletedException().error, servedStale = false))
                    return
                }

                is FetchOutcome.Failed -> {
                    send(StoreResult.Error(outcome.exception.error, servedStale = servedResident))
                    return
                }

                FetchOutcome.Superseded -> Unit
            }
        }
    }

    /** Computes an honest Data emission from typed metadata and the current state snapshot. */
    private fun toData(
        envelope: ValueEnvelope<V>,
        freshness: Freshness,
        refreshingOverride: Boolean? = null,
    ): StoreResult.Data<V> {
        val snapshot = state.value
        val now = wallClock.nowEpochMillis()
        val elapsedMillis =
            if (now <= envelope.meta.writtenAtEpochMillis) {
                0L
            } else {
                val delta = now - envelope.meta.writtenAtEpochMillis
                if (delta < 0L) Long.MAX_VALUE else delta
            }
        val age = elapsedMillis.milliseconds
        val epochStale = envelope.staleEpochAtCommit < snapshot.staleEpoch
        val ageStale = freshness is Freshness.MaxAge && age > freshness.notOlderThan
        return StoreResult.Data(
            value = envelope.value,
            origin = envelope.origin,
            age = age,
            isStale = epochStale || ageStale,
            refreshing = refreshingOverride ?: (snapshot.fetch is FetchSlot.InFlight),
        )
    }

    /** Returns a value according to the requested freshness policy. */
    internal suspend fun get(freshness: Freshness): V {
        ensureOpen()
        val envelope = residence.value
        val plan = currentPlan(freshness, envelope)

        if (plan is FetchPlan.Skip) {
            return envelope?.value ?: throw localOnlyMissingException()
        }

        if (envelope != null && plan.servesResident && freshness != Freshness.StaleIfError) {
            ensureFetch(freshness)
            return envelope.value
        }

        val ticket =
            ensureFetch(freshness)
                ?: return residence.value?.value ?: throw supersededException()

        return when (val outcome = ticket.outcome.await()) {
            FetchOutcome.Committed -> residence.value?.value ?: throw supersededException()
            FetchOutcome.Revalidated ->
                residence.value?.value ?: throw notModifiedWithoutValueException()

            is FetchOutcome.Failed ->
                if (freshness == Freshness.StaleIfError && envelope != null) {
                    envelope.value
                } else {
                    throw outcome.exception
                }

            FetchOutcome.Superseded -> throw supersededException()
            FetchOutcome.Deleted -> throw serverDeletedException()
        }
    }

    /** Fails deterministically when this engine's store scope is no longer active. */
    private fun ensureOpen() {
        if (!engineJob.isActive) {
            throw storeClosedException()
        }
    }
}
