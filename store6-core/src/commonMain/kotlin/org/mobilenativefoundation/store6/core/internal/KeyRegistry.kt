package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.StoreKey

/** Owns exactly one [KeyEngine] for each canonical [KeyId]. */
internal class KeyRegistry<K : StoreKey, V : Any>(
    private val createEngine: (K, KeyId) -> KeyEngine<K, V>,
) {
    private val lock = Mutex()
    private val engines = HashMap<KeyId, KeyEngine<K, V>>()

    /**
     * Resolves the engine for [key] and invokes [block] without holding the registry lock.
     *
     * Engine creation is serialized, while work performed by different engines remains
     * independent. Creation is also where canonical-identity stability is verified: the check
     * runs once per key residency, which keeps it always-on at negligible cost.
     */
    internal suspend fun <R> withEngine(
        key: K,
        block: suspend (KeyEngine<K, V>) -> R,
    ): R {
        val engine =
            lock.withLock {
                val id = KeyId.from(key)
                engines.getOrPut(id) {
                    verifyStableCanonicalId(key, id)
                    createEngine(key, id)
                }
            }
        return block(engine)
    }

    /**
     * Applies [action] to every resident engine, filtered to [namespace] when one is given.
     *
     * The engine list is snapshotted under the lock and acted on outside it. Durable invalidation
     * watermarks cover engines missed by a snapshot. Bulk clear deliberately sweeps twice under a
     * scoped commit fence: an engine inserted between snapshots is included by purge, while one
     * inserted after purge cannot have observed a pre-delete row and its later commit remains
     * fenced until maintenance releases.
     */
    internal suspend fun forEachResident(
        namespace: String?,
        action: suspend (KeyEngine<K, V>) -> Unit,
    ) {
        val targets =
            lock.withLock {
                engines.entries
                    .filter { namespace == null || it.key.namespace == namespace }
                    .map { it.value }
            }
        targets.forEach { engine -> action(engine) }
    }

    /** Returns the exact resident snapshot acted on when a caller must notify it after a fence. */
    internal suspend fun snapshotAndForEachResident(
        namespace: String?,
        action: suspend (KeyEngine<K, V>) -> Unit,
    ): List<KeyEngine<K, V>> {
        val targets =
            lock.withLock {
                engines.entries
                    .filter { namespace == null || it.key.namespace == namespace }
                    .map { it.value }
            }
        targets.forEach { engine -> action(engine) }
        return targets
    }

    /** Fails fast when a key's canonical identity is not stable across calls (FS-6). */
    private fun verifyStableCanonicalId(
        key: K,
        id: KeyId,
    ) {
        val second = key.canonicalId()
        check(id.canonicalId == second) {
            "StoreKey ${key::class.simpleName ?: "<anonymous>"} in namespace " +
                "'${id.namespace}' returned two different canonical ids for the same " +
                "instance ('${id.canonicalId}', then '$second'). canonicalId() is the key's " +
                "durable identity and must be a pure, stable function of the key's immutable " +
                "fields. Fix the key type so repeated calls always return the same string."
        }
    }
}
