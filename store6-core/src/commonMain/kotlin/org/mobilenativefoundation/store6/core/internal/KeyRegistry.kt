package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.StoreKey

/** Owns exactly one [KeyEngine] for each canonical [KeyId]. */
internal class KeyRegistry<K : StoreKey, V : Any>(
    private val createEngine: (K) -> KeyEngine<K, V>,
) {
    private val lock = Mutex()
    private val engines = HashMap<KeyId, KeyEngine<K, V>>()

    /**
     * Resolves the engine for [key] and invokes [block] without holding the registry lock.
     *
     * Engine creation is serialized, while work performed by different engines remains
     * independent.
     */
    internal suspend fun <R> withEngine(
        key: K,
        block: suspend (KeyEngine<K, V>) -> R,
    ): R {
        val engine =
            lock.withLock {
                engines.getOrPut(KeyId.from(key)) { createEngine(key) }
            }
        return block(engine)
    }
}
