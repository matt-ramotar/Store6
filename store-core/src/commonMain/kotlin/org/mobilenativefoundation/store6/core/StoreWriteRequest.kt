package org.mobilenativefoundation.store6.core

import kotlinx.datetime.Clock
import org.mobilenativefoundation.store6.core.impl.OnStoreWriteCompletion
import org.mobilenativefoundation.store6.core.impl.RealStoreWriteRequest

interface StoreWriteRequest<Key : Any, Output : Any, Response : Any> {
    val key: Key
    val value: Output
    val created: Long
    val onCompletions: List<OnStoreWriteCompletion>?

    companion object {
        fun <Key : Any, Output : Any, Response : Any> of(
            key: Key,
            value: Output,
            onCompletions: List<OnStoreWriteCompletion>? = null,
            created: Long = Clock.System.now().toEpochMilliseconds(),
        ): StoreWriteRequest<Key, Output, Response> = RealStoreWriteRequest(key, value, created, onCompletions)
    }
}
