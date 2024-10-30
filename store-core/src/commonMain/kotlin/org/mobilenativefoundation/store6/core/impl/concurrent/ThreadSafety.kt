package org.mobilenativefoundation.store6.core.impl.concurrent

import kotlinx.coroutines.sync.Mutex

internal data class ThreadSafety(
    val writeRequests: StoreThreadSafety = StoreThreadSafety(),
    val readCompletions: StoreThreadSafety = StoreThreadSafety(),
)

internal data class StoreThreadSafety(
    val mutex: Mutex = Mutex(),
    val lightswitch: Lightswitch = Lightswitch(),
)
