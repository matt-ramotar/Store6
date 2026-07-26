package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the two zero-config defaults the published Important Defaults page states but that no other
 * conformance test names as *the default*: the default [Freshness] and the absence of fetcher
 * retries. Both lines are public documentation, so both get a test that fails when the
 * documentation stops being true.
 */
@OptIn(ExperimentalStoreApi::class)
class StoreDefaultsPinTest {
    /**
     * The default [Freshness] is [Freshness.CachedOrFetch]. Two observations distinguish it from
     * the alternatives: an absent key fetches (so the default is not [Freshness.LocalOnly]), and a
     * resident fresh value is served without a second fetch (so it is not
     * [Freshness.MustBeFresh]).
     */
    @Test
    fun defaultFreshness_isCachedOrFetch_zeroConfig() =
        runTest(timeout = 60.seconds) {
            val fetcher = CountingFetcher()
            val store =
                store<TestKey, String> {
                    fetcher(fetcher::fetch)
                }

            try {
                val key = TestKey("default-freshness")

                assertEquals("v1:default-freshness", store.get(key))
                assertEquals(1, fetcher.count, "an absent key must fetch: the default is not LocalOnly")

                assertEquals("v1:default-freshness", store.get(key))
                assertEquals(
                    1,
                    fetcher.count,
                    "a resident fresh value must be served without a second fetch: " +
                        "the default is not MustBeFresh",
                )
            } finally {
                store.close()
            }
        }

    /**
     * The engine never retries your fetcher. One demand cycle invokes it exactly once, a failure
     * schedules no background retry and no backoff, and a later call is a new demand rather than a
     * continuation of the failed one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fetcherFailure_isNotRetried_zeroConfig() =
        runTest(timeout = 60.seconds) {
            val fetcher = AlwaysFailingFetcher()
            val store =
                store<TestKey, String> {
                    fetcher(fetcher::fetch)
                }

            try {
                val key = TestKey("no-retry")

                assertFailsWith<StoreException> { store.get(key, Freshness.MustBeFresh) }
                assertEquals(1, fetcher.count, "one demand cycle invokes the fetcher exactly once")

                advanceUntilIdle()
                assertEquals(1, fetcher.count, "a failed fetch schedules no background retry")

                assertFailsWith<StoreException> { store.get(key, Freshness.MustBeFresh) }
                assertEquals(2, fetcher.count, "a second call is a new demand, not a retry")
            } finally {
                store.close()
            }
        }

    private class CountingFetcher {
        var count: Int = 0
            private set

        fun fetch(key: TestKey): String {
            count++
            return "v$count:${key.canonicalId()}"
        }
    }

    private class AlwaysFailingFetcher {
        var count: Int = 0
            private set

        fun fetch(key: TestKey): String {
            count++
            throw IllegalStateException("fetch failed for ${key.canonicalId()}")
        }
    }
}
