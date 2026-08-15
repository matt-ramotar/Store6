@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.paging

import androidx.paging.PagingSource
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PagingWiringSpikeTest {
    @Test
    fun load_returnsStaticPage() = runTest {
        val result =
            SpikePagingSource().load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 3,
                    placeholdersEnabled = false,
                ),
            )

        assertEquals(
            PagingSource.LoadResult.Page<Int, String>(
                data = listOf("static"),
                prevKey = null,
                nextKey = null,
            ),
            result,
        )
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
