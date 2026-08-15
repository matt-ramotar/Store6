package org.mobilenativefoundation.store6.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState

internal class SpikePagingSource : PagingSource<Int, String>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> =
        LoadResult.Page(
            data = listOf("static"),
            prevKey = null,
            nextKey = null,
        )

    override fun getRefreshKey(state: PagingState<Int, String>): Int? = null
}
