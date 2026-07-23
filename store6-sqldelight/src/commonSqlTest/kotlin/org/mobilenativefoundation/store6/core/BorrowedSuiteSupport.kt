package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.WallClock

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class FakeWallClock(var now: Long) : WallClock {
    override fun nowEpochMillis(): Long = now
}

@OptIn(ExperimentalStoreApi::class)
internal fun <K : StoreKey, V : Any> storeWith(
    clock: WallClock? = null,
    bookkeeper: Bookkeeper? = null,
    configure: StoreBuilder<K, V>.() -> Unit,
): Store<K, V> = store {
    clock?.let { wallClock(it) }
    bookkeeper?.let { this.bookkeeper(it) }
    configure()
}

/**
 * Re-derivation of core's test-support shutdown for the borrowed compilation. This module cannot
 * reference core internals (CI guard + module visibility), so settle is close() only: engine
 * coroutines are cancelled but not joined. Cleanup-only; the borrowed scenarios' assertions never
 * depend on joined shutdown.
 */
internal suspend fun Store<*, *>.closeAndSettleForTest() {
    close()
}
