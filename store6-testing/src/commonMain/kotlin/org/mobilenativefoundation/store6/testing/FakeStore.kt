package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Programmable [Store] for deterministic tests: scripted outcomes, recorded interactions, honest
 * result flags, and one failure channel. [Freshness] values are recorded but never interpreted;
 * compose the seam fakes into a real `store {}` when testing engine policy.
 *
 * Per-key histories are append-only and unbounded for the lifetime of this test-scoped fake. Every
 * history frame is followed by [yield], deliberately providing stronger delivery than the engine
 * so StateFlow/stateIn consumers can assert each lifecycle frame without conflation.
 *
 * Invalidation implements Decision #37 (Matt, 2026-07-20): it only stale-marks residence and never
 * consumes a script. Scripted staleness is consumed at the next demand from an active or later
 * stream, or behind a stale [get] read, with a single CAS consumption site for invalidated cells.
 *
 * `runtime()` returns null by design. [FakeStore] produces no KeyEvents and performs no overlay
 * projection; runtime, events, overlays, and freshness-policy behavior belong to a real store.
 *
 * The seam consumed here is a FREEZE CANDIDATE, not frozen: freeze sign-off remains held until
 * issue 007 lands and Matt signs off. Close semantics and message text are
 * PROVISIONAL-PENDING-007 and must be re-verified when 007 lands.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FakeStore<K : StoreKey, V : Any>(
    public val wallClock: TestWallClock = TestWallClock(),
) : Store<K, V> {
    private data class Cell<V : Any>(
        val value: V?,
        val origin: Origin,
        val writtenAtEpochMillis: Long,
        val isStale: Boolean,
        val script: List<Scripted<V>>,
        val history: List<StoreResult<V>>,
    )

    private sealed class Scripted<out V : Any> {
        class Value<V : Any>(val value: V) : Scripted<V>()

        class Failure(
            val error: StoreError,
            val servedStale: Boolean,
        ) : Scripted<Nothing>()

        class Revalidated(val age: Duration) : Scripted<Nothing>()
    }

    private val cells = MutableStateFlow<Map<Pair<String, String>, Cell<V>>>(emptyMap())
    private val closed = MutableStateFlow(false)
    private val recorded = MutableStateFlow<List<FakeStoreInteraction>>(emptyList())

    public val interactions: List<FakeStoreInteraction>
        get() = recorded.value

    public fun clearInteractions() {
        recorded.value = emptyList()
    }

    public fun setValue(
        key: K,
        value: V,
        origin: Origin = Origin.MEMORY,
        isStale: Boolean = false,
    ) {
        val id = idOf(key)
        cells.update { map ->
            val cell = map[id] ?: emptyCell()
            val now = wallClock.nowEpochMillis()
            map +
                (
                    id to
                        cell.copy(
                            value = value,
                            origin = origin,
                            writtenAtEpochMillis = now,
                            isStale = isStale,
                            history =
                                cell.history +
                                    dataOf(
                                        value,
                                        origin,
                                        now,
                                        isStale,
                                        refreshing = false,
                                    ),
                        )
                )
        }
    }

    public fun enqueueFetchValue(key: K, value: V) {
        enqueue(key, Scripted.Value(value))
    }

    public fun enqueueFetchError(
        key: K,
        error: StoreError,
        servedStale: Boolean = false,
    ) {
        enqueue(key, Scripted.Failure(error, servedStale))
    }

    public fun enqueueFetchRevalidated(
        key: K,
        age: Duration = Duration.ZERO,
    ) {
        enqueue(key, Scripted.Revalidated(age))
    }

    override fun stream(key: K, freshness: Freshness): Flow<StoreResult<V>> {
        ensureOpen()
        return flow {
            ensureOpen()
            record(FakeStoreInteraction.Stream(key, freshness))
            val id = idOf(key)
            val before = cells.value[id]
            var cursor = before?.history?.size ?: 0
            if (before?.value != null) {
                emit(
                    dataOf(
                        before.value,
                        before.origin,
                        before.writtenAtEpochMillis,
                        before.isStale,
                        refreshing = before.isStale && before.script.isNotEmpty(),
                    ),
                )
            } else {
                emit(TestStoreResults.loading())
                consumeIfAbsent(key)
            }
            combine(cells, closed) { map, isClosed -> map[id] to isClosed }
                .collect { (cell, isClosed) ->
                    if (isClosed) throw CancellationException(CLOSED_MESSAGE)
                    val history = cell?.history.orEmpty()
                    while (cursor < history.size) {
                        emit(history[cursor])
                        cursor += 1
                        // Per-frame dispatch pin: deliberately stronger test delivery prevents
                        // StateFlow/stateIn consumers from losing intermediate lifecycle frames.
                        yield()
                    }
                    // Decision #37: an active collector is demand, but invalidate itself never
                    // consumes. CAS allows only one collector to claim each queued outcome.
                    if (cell?.value != null && cell.isStale && cell.script.isNotEmpty()) {
                        consumeIfStale(key)
                    }
                }
        }
    }

    override suspend fun get(key: K, freshness: Freshness): V {
        ensureOpen()
        record(FakeStoreInteraction.Get(key, freshness))
        val cell = cells.value[idOf(key)]
        val resident = cell?.value
        if (resident != null) {
            // Decision #37 SWR mirror: return stale residence and commit one queued outcome behind
            // this read. The next read observes the committed refresh.
            if (cell.isStale) consumeIfStale(key)
            return resident
        }
        return when (val consumed = consumeIfAbsent(key)) {
            is Scripted.Value -> consumed.value
            is Scripted.Failure -> throw TestStoreResults.exception(consumed.error)
            is Scripted.Revalidated ->
                throw missingException(
                    key,
                    "a scripted Revalidated confirmed freshness but no resident value exists",
                )
            null -> throw missingException(key, "no resident value and no scripted fetch exist")
        }
    }

    override suspend fun invalidate(key: K) {
        ensureOpen()
        record(FakeStoreInteraction.Invalidate(key))
        invalidateCell(idOf(key))
    }

    override suspend fun invalidateNamespace(namespace: StoreNamespace) {
        ensureOpen()
        record(FakeStoreInteraction.InvalidateNamespace(namespace))
        cells.value.keys.filter { it.first == namespace.value }.forEach(::invalidateCell)
    }

    override suspend fun invalidateAll() {
        ensureOpen()
        record(FakeStoreInteraction.InvalidateAll)
        cells.value.keys.forEach(::invalidateCell)
    }

    override suspend fun clear(key: K) {
        ensureOpen()
        record(FakeStoreInteraction.Clear(key))
        clearCell(idOf(key))
    }

    override suspend fun clearNamespace(namespace: StoreNamespace) {
        ensureOpen()
        record(FakeStoreInteraction.ClearNamespace(namespace))
        cells.value.keys.filter { it.first == namespace.value }.forEach(::clearCell)
    }

    override suspend fun clearAll() {
        ensureOpen()
        record(FakeStoreInteraction.ClearAll)
        cells.value.keys.forEach(::clearCell)
    }

    /**
     * Closes this fake synchronously and idempotently.
     * PROVISIONAL-PENDING-007: re-verify exception types and message text when issue 007 lands.
     */
    override fun close() {
        if (closed.value) return
        record(FakeStoreInteraction.Close)
        closed.value = true
    }

    private fun idOf(key: K): Pair<String, String> =
        key.namespace.value to key.canonicalId()

    private fun emptyCell(): Cell<V> =
        Cell(
            value = null,
            origin = Origin.FETCHER,
            writtenAtEpochMillis = 0L,
            isStale = false,
            script = emptyList(),
            history = emptyList(),
        )

    private fun record(interaction: FakeStoreInteraction) {
        recorded.update { it + interaction }
    }

    private fun ensureOpen() {
        if (closed.value) throw IllegalStateException(CLOSED_MESSAGE)
    }

    private fun ageOf(writtenAtEpochMillis: Long): Duration =
        (wallClock.nowEpochMillis() - writtenAtEpochMillis).coerceAtLeast(0L).milliseconds

    private fun dataOf(
        value: V,
        origin: Origin,
        writtenAt: Long,
        isStale: Boolean,
        refreshing: Boolean,
    ): StoreResult.Data<V> =
        TestStoreResults.data(value, origin, ageOf(writtenAt), isStale, refreshing)

    private fun missingException(key: K, reason: String): StoreException =
        TestStoreResults.exception(
            TestStoreResults.missing(
                key,
                "FakeStore.get could not return a value for key " +
                    "${key.namespace.value}/${key.canonicalId()}: $reason. Seed setValue() or " +
                    "script enqueueFetchValue()/enqueueFetchError() before reading.",
            ),
        )

    private fun enqueue(key: K, scripted: Scripted<V>) {
        val id = idOf(key)
        cells.update { map ->
            val cell = map[id] ?: emptyCell()
            map + (id to cell.copy(script = cell.script + scripted))
        }
    }

    /**
     * Consumes the script head when no resident value exists; at most one consumer wins (CAS).
     * Owns the absent-Revalidated case because it holds the real key for the Missing payload.
     */
    private fun consumeIfAbsent(key: K): Scripted<V>? {
        val id = idOf(key)
        while (true) {
            val current = cells.value
            val cell = current[id]
            if (cell?.value != null) return null
            val head = cell?.script?.firstOrNull() ?: return null
            val next =
                when (head) {
                    is Scripted.Revalidated ->
                        cell.copy(
                            script = cell.script.drop(1),
                            history =
                                cell.history +
                                    TestStoreResults.error(
                                        TestStoreResults.missing(
                                            key,
                                            "a scripted Revalidated arrived with no resident " +
                                                "value for ${key.namespace.value}/" +
                                                "${key.canonicalId()}. Script " +
                                                "enqueueFetchValue() first or seed setValue().",
                                        ),
                                        servedStale = false,
                                    ),
                        )
                    else -> applyScript(cell, head)
                }
            if (cells.compareAndSet(current, current + (id to next))) return head
        }
    }

    /**
     * Consumes the script head against a stale resident. This is the single demand-driven CAS
     * consumption site for invalidated cells under Decision #37, called from stale [get] demand
     * and from the stream collector loop for active or later stream demand.
     */
    private fun consumeIfStale(key: K) {
        val id = idOf(key)
        while (true) {
            val current = cells.value
            val cell = current[id] ?: return
            if (cell.value == null || !cell.isStale) return
            val head = cell.script.firstOrNull() ?: return
            if (cells.compareAndSet(current, current + (id to applyScript(cell, head)))) return
        }
    }

    /**
     * Applies one scripted outcome. Revalidated requires residence here; [consumeIfAbsent] owns
     * the absent case because it holds the key used for the Missing payload.
     */
    private fun applyScript(cell: Cell<V>, head: Scripted<V>): Cell<V> {
        val now = wallClock.nowEpochMillis()
        val rest = cell.script.drop(1)
        return when (head) {
            is Scripted.Value ->
                cell.copy(
                    value = head.value,
                    origin = Origin.FETCHER,
                    writtenAtEpochMillis = now,
                    isStale = false,
                    script = rest,
                    history =
                        cell.history +
                            dataOf(
                                head.value,
                                Origin.FETCHER,
                                now,
                                isStale = false,
                                refreshing = false,
                            ),
                )
            is Scripted.Failure ->
                cell.copy(
                    script = rest,
                    history =
                        cell.history +
                            TestStoreResults.error(head.error, head.servedStale),
                )
            is Scripted.Revalidated -> {
                check(cell.value != null) {
                    "applyScript requires a resident value for Revalidated; " +
                        "consumeIfAbsent owns the absent case."
                }
                cell.copy(
                    writtenAtEpochMillis = now,
                    isStale = false,
                    script = rest,
                    history = cell.history + TestStoreResults.revalidated(head.age),
                )
            }
        }
    }

    /**
     * Decision #37 (ruled by Matt, 2026-07-20): invalidate is a stale-mark only, the engine's
     * epoch-bump analog. It appends the stale re-emission frame for active collectors and never
     * consumes a script; stale get, active stream, or later stream demand owns consumption.
     */
    private fun invalidateCell(id: Pair<String, String>) {
        cells.update { map ->
            val cell = map[id] ?: return@update map
            if (cell.value == null) return@update map
            val refreshing = cell.script.isNotEmpty()
            map +
                (
                    id to
                        cell.copy(
                            isStale = true,
                            history =
                                cell.history +
                                    dataOf(
                                        cell.value,
                                        cell.origin,
                                        cell.writtenAtEpochMillis,
                                        isStale = true,
                                        refreshing = refreshing,
                                    ),
                        )
                )
        }
    }

    private fun clearCell(id: Pair<String, String>) {
        cells.update { map ->
            val cell = map[id] ?: return@update map
            if (cell.value == null) return@update map
            map +
                (
                    id to
                        cell.copy(
                            value = null,
                            isStale = false,
                            history = cell.history + TestStoreResults.loading(),
                        )
                )
        }
    }

    private companion object {
        // PROVISIONAL-PENDING-007: core's STORE_CLOSED_MESSAGE is internal and cannot be imported.
        // If 007 publishes a shared constant, delegate-and-delete this local literal, then re-verify
        // every close-semantics pin.
        private const val CLOSED_MESSAGE = "Store is closed."
    }
}
