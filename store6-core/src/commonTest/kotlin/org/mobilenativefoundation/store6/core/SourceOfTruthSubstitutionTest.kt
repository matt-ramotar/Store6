@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.WallClock

/** Installs an alternate source of truth without changing public Store conformance scenarios. */
abstract class SourceOfTruthSubstitutionTest {
    protected open fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) = Unit

    /**
     * Test-fixture acknowledgement for adversarial reader decorators. Default and ordinary
     * substitution runs have no extra reader hop, so their acknowledgement is immediate.
     */
    protected open suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) = Unit

    protected fun <K : StoreKey, V : Any> testStore(
        configure: StoreBuilder<K, V>.() -> Unit,
    ): Store<K, V> =
        store {
            configure()
            installSot(this)
        }

    /** Preserves injected test seams while routing the Store through the same substitution hook. */
    internal fun <K : StoreKey, V : Any> testStoreWith(
        clock: WallClock? = null,
        bookkeeper: Bookkeeper? = null,
        configure: StoreBuilder<K, V>.() -> Unit,
    ): Store<K, V> =
        storeWith(clock = clock, bookkeeper = bookkeeper) {
            configure()
            installSot(this)
        }
}
