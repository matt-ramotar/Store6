package org.mobilenativefoundation.store6.paging

import androidx.paging.InvalidatingPagingSourceFactory
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Builds an androidx [InvalidatingPagingSourceFactory] over this store.
 *
 * Each paging source performs a single-shot load by collecting [Store.stream] to its first
 * terminal outcome. It never calls `Store.get`, so values projected by a configured stream
 * overlay remain visible. Calling `invalidate()` on the returned factory invalidates every
 * paging source previously created by that factory.
 *
 * @throws IllegalStateException if [configure] omits a required builder door
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any, PK : Any, Item : Any> Store<K, V>.pagingSourceFactory(
    configure: StorePagingBuilder<K, V, PK, Item>.() -> Unit,
): InvalidatingPagingSourceFactory<PK, Item> {
    val config = StorePagingBuilder<K, V, PK, Item>().apply(configure).build()
    return InvalidatingPagingSourceFactory(
        pagingSourceFactory = { StorePagingSource(this, config) },
    )
}
