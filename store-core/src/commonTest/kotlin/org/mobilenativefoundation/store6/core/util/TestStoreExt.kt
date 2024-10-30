package org.mobilenativefoundation.store6.core.util

import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreReadRequest
import org.mobilenativefoundation.store6.core.StoreReadResponse
import org.mobilenativefoundation.store6.core.impl.operators.mapIndexed

/**
 * Helper factory that will return [StoreReadResponse.Data] for [key]
 * if it is cached otherwise will return fresh/network data (updating your caches)
 */
suspend fun <Key : Any, Output : Any> Store<Key, Output>.getData(key: Key) =
    stream(
        StoreReadRequest.cached(key, refresh = false),
    ).filterNot {
        it is StoreReadResponse.Loading
    }.mapIndexed { index, value ->
        value
    }.first().let {
        StoreReadResponse.Data(it.requireData(), it.origin)
    }
