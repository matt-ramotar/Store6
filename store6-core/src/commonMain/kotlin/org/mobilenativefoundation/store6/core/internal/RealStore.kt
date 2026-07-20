package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.core.seam.WallClock

/**
 * Store implementation backed by one supervised [KeyEngine] per canonical key.
 *
 * Each engine receives its own supervised child scope. Closing the store cancels the parent job
 * and all active engine work without allowing one key's fetch failure to cancel another key.
 * Freshness policies are honored per the [Freshness] contract; planning is delegated to the
 * engine's validator.
 */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class RealStore<K : StoreKey, V : Any>(
    fetcher: Fetcher<K, V>,
    private val sot: SourceOfTruth<K, V>,
    wallClock: WallClock,
    private val bookkeeper: Bookkeeper,
    validator: FreshnessValidator,
    private val telemetry: StoreTelemetry?,
) : Store<K, V> {
    private val storeJob = SupervisorJob()
    private val storeScope = CoroutineScope(Dispatchers.Default + storeJob)
    private val maintenanceCoordinator = MaintenanceCoordinator()
    private val registry =
        KeyRegistry<K, V> { key, id ->
            val engineJob = SupervisorJob(storeJob)
            KeyEngine(
                key = key,
                keyId = id,
                fetcher = fetcher,
                sot = sot,
                bookkeeper = bookkeeper,
                validator = validator,
                wallClock = wallClock,
                telemetry = telemetry,
                engineScope = CoroutineScope(storeScope.coroutineContext + engineJob),
                maintenanceCoordinator = maintenanceCoordinator,
            )
        }

    override fun stream(
        key: K,
        freshness: Freshness,
    ): Flow<StoreResult<V>> {
        ensureOpen()
        return flow {
            ensureOpen()
            registry.withEngine(key) { engine ->
                emitAll(engine.stream(freshness))
            }
        }
    }

    override suspend fun get(
        key: K,
        freshness: Freshness,
    ): V {
        ensureOpen()
        return registry.withEngine(key) { engine -> engine.get(freshness) }
    }

    override suspend fun invalidate(key: K) {
        ensureOpen()
        registry.withEngine(key) { engine -> engine.invalidate() }
    }

    override suspend fun invalidateNamespace(namespace: StoreNamespace) {
        ensureOpen()
        durably("invalidateNamespace", "namespace '${namespace.value}'") {
            bookkeeper.advanceStaleWatermark(namespace)
        }
        registry.forEachResident(namespace.value) { engine -> engine.invalidateResident() }
    }

    override suspend fun invalidateAll() {
        ensureOpen()
        durably("invalidateAll", "all namespaces") {
            bookkeeper.advanceGlobalStaleWatermark()
        }
        registry.forEachResident(namespace = null) { engine -> engine.invalidateResident() }
    }

    override suspend fun clear(key: K) {
        ensureOpen()
        registry.withEngine(key) { engine -> engine.clear() }
    }

    override suspend fun clearNamespace(namespace: StoreNamespace) {
        ensureOpen()
        if (telemetry == null) {
            maintenanceCoordinator.withNamespaceMaintenance(namespace.value) {
                registry.forEachResident(namespace.value) { engine -> engine.clearResident() }
                durably("clearNamespace", "namespace '${namespace.value}'") {
                    sot.deleteNamespace(namespace)
                }
                durably("clearNamespace", "namespace '${namespace.value}'") {
                    bookkeeper.forgetNamespace(namespace)
                }
                registry.forEachResident(namespace.value) { engine -> engine.clearResident() }
            }
            return
        }
        val cleared =
            maintenanceCoordinator.withNamespaceMaintenance(namespace.value) {
                val firstSweep =
                    registry.snapshotAndForEachResident(namespace.value) { engine ->
                        engine.clearResident()
                    }
                durably("clearNamespace", "namespace '${namespace.value}'") {
                    sot.deleteNamespace(namespace)
                }
                durably("clearNamespace", "namespace '${namespace.value}'") {
                    bookkeeper.forgetNamespace(namespace)
                }
                registry.forEachResident(namespace.value) { engine -> engine.clearResident() }
                firstSweep
            }
        cleared.forEach { engine -> engine.notifyBulkClearCompleted() }
    }

    override suspend fun clearAll() {
        ensureOpen()
        if (telemetry == null) {
            maintenanceCoordinator.withGlobalMaintenance {
                registry.forEachResident(namespace = null) { engine -> engine.clearResident() }
                durably("clearAll", "all namespaces") { sot.deleteAll() }
                durably("clearAll", "all namespaces") { bookkeeper.forgetAll() }
                registry.forEachResident(namespace = null) { engine -> engine.clearResident() }
            }
            return
        }
        val cleared =
            maintenanceCoordinator.withGlobalMaintenance {
                val firstSweep =
                    registry.snapshotAndForEachResident(namespace = null) { engine ->
                        engine.clearResident()
                    }
                durably("clearAll", "all namespaces") { sot.deleteAll() }
                durably("clearAll", "all namespaces") { bookkeeper.forgetAll() }
                registry.forEachResident(namespace = null) { engine -> engine.clearResident() }
                firstSweep
            }
        cleared.forEach { engine -> engine.notifyBulkClearCompleted() }
    }

    override fun close() {
        storeJob.cancel(CancellationException(STORE_CLOSED_MESSAGE))
    }

    /** Fails deterministically when an operation starts after [close]. */
    private fun ensureOpen() {
        if (!storeJob.isActive) {
            throw storeClosedException()
        }
    }

    /** Runs one durable maintenance step, preserving cancellation and typing other failures. */
    private suspend fun durably(
        operation: String,
        scope: String,
        step: suspend () -> Unit,
    ) {
        try {
            step()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val message =
                "$operation failed for $scope: ${failure.message}. Durable maintenance runs in " +
                    "a fixed order (stale mark before signal, delete before forget); completed " +
                    "steps remain applied and are conservative. Retry the operation and inspect " +
                    "the cause for the underlying persistence failure."
            throw StoreException(
                error = StoreError.Persistence(message = message, cause = failure),
                cause = failure,
            )
        }
    }
}
