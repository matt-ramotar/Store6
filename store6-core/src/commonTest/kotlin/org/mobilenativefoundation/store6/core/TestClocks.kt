package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.internal.Bookkeeper
import org.mobilenativefoundation.store6.core.internal.WallClock

internal class FakeWallClock(var now: Long) : WallClock {
    override fun nowEpochMillis(): Long = now
}

internal fun <K : StoreKey, V : Any> storeWith(
    clock: WallClock? = null,
    bookkeeper: Bookkeeper? = null,
    configure: StoreBuilder<K, V>.() -> Unit,
): Store<K, V> =
    StoreBuilder<K, V>().apply {
        clock?.let { wallClock = it }
        bookkeeper?.let { this.bookkeeper = it }
        configure()
    }.build()
