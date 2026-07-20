package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

open class FreshnessPolicyConformanceTest : SourceOfTruthSubstitutionTest() {
    @Test
    fun maxAgeWithinBoundServesResidentWithoutSecondFetch() = runTest {
        val clock = FakeWallClock(now = 0L)
        var calls = 0
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher { "v${++calls}" }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            clock.now = 60.seconds.inWholeMilliseconds

            assertEquals(
                "v1",
                store.get(TestKey("1"), Freshness.MaxAge(notOlderThan = 5.minutes)),
            )
            assertEquals(1, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun maxAgeOverBoundGetBlocksForAndReturnsFreshValue() = runTest {
        val clock = FakeWallClock(now = 0L)
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            clock.now = 600.seconds.inWholeMilliseconds

            val fresh =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(TestKey("1"), Freshness.MaxAge(notOlderThan = 5.minutes))
                }
            assertFalse(fresh.isCompleted)
            secondStarted.await()
            secondGate.complete(Unit)

            assertEquals("v2", fresh.await())
            assertEquals(2, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun maxAgeOverBoundStreamWithholdsResidentUntilFreshValue() = runTest {
        val clock = FakeWallClock(now = 0L)
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            clock.now = 600.seconds.inWholeMilliseconds

            store.stream(
                TestKey("1"),
                Freshness.MaxAge(notOlderThan = 5.minutes),
            ).test {
                assertIs<StoreResult.Loading>(awaitItem())
                secondStarted.await()
                secondGate.complete(Unit)

                val fresh = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v2", fresh.value)
                assertEquals(Duration.ZERO, fresh.age)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun maxAgeOverBoundFailureDoesNotFallBackForGetOrStream() = runTest {
        val clock = FakeWallClock(now = 0L)
        val boom = IllegalStateException("boom")
        var calls = 0
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher {
                if (++calls == 1) "v1" else throw boom
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            clock.now = 600.seconds.inWholeMilliseconds

            val getFailure =
                assertFailsWith<StoreException> {
                    store.get(TestKey("1"), Freshness.MaxAge(notOlderThan = 5.minutes))
                }
            val getFetch = assertIs<StoreError.Fetch>(getFailure.error)
            assertTrue(getFetch.cause === boom)

            store.stream(
                TestKey("1"),
                Freshness.MaxAge(notOlderThan = 5.minutes),
            ).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val failure = assertIs<StoreResult.Error>(awaitItem())
                val streamFetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(streamFetch.cause === boom)
                assertFalse(failure.servedStale)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun mustBeFreshRefetchesFreshResident() = runTest {
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher { "v${++calls}" }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            assertEquals("v2", store.get(TestKey("1"), Freshness.MustBeFresh))
            assertEquals(2, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun mustBeFreshFailureHasNoFallbackAndStreamCompletes() = runTest {
        val boom = IllegalStateException("boom")
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher {
                if (++calls == 1) "v1" else throw boom
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))

            val getFailure =
                assertFailsWith<StoreException> {
                    store.get(TestKey("1"), Freshness.MustBeFresh)
                }
            val getFetch = assertIs<StoreError.Fetch>(getFailure.error)
            assertTrue(getFetch.cause === boom)

            store.stream(TestKey("1"), Freshness.MustBeFresh).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val failure = assertIs<StoreResult.Error>(awaitItem())
                val streamFetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(streamFetch.cause === boom)
                assertFalse(failure.servedStale)
                awaitComplete()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun staleIfErrorAfterInvalidationWaitsForFailureThenReturnsResident() = runTest {
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val boom = IllegalStateException("boom")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        throw boom
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            store.invalidate(TestKey("1"))

            val fallback =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(TestKey("1"), Freshness.StaleIfError)
                }
            assertFalse(fallback.isCompleted)
            secondStarted.await()
            secondGate.complete(Unit)

            assertEquals("v1", fallback.await())
            assertEquals(2, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun staleIfErrorWithoutResidentThrowsFetch() = runTest {
        val boom = IllegalStateException("boom")
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher {
                calls++
                throw boom
            }
        }

        try {
            val failure =
                assertFailsWith<StoreException> {
                    store.get(TestKey("1"), Freshness.StaleIfError)
                }
            val fetch = assertIs<StoreError.Fetch>(failure.error)
            assertTrue(fetch.cause === boom)
            assertEquals(1, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun staleIfErrorStreamEmitsStaleThenServedStaleErrorAndStaysLive() = runTest {
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val boom = IllegalStateException("boom")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        throw boom
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            store.invalidate(TestKey("1"))

            store.stream(TestKey("1"), Freshness.StaleIfError).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                secondStarted.await()
                secondGate.complete(Unit)

                val failure = assertIs<StoreResult.Error>(awaitItem())
                val fetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(fetch.cause === boom)
                assertTrue(failure.servedStale)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun localOnlyWithoutResidentReportsMissingWithoutLoadingOrFetcherCall() = runTest {
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher {
                calls++
                "remote"
            }
        }

        try {
            val getFailure =
                assertFailsWith<StoreException> {
                    store.get(TestKey("1"), Freshness.LocalOnly)
                }
            val getMissing = assertIs<StoreError.Missing>(getFailure.error)
            assertTrue(getMissing.message.contains("LocalOnly"))

            store.stream(TestKey("1"), Freshness.LocalOnly).test {
                val failure = assertIs<StoreResult.Error>(awaitItem())
                val streamMissing = assertIs<StoreError.Missing>(failure.error)
                assertTrue(streamMissing.message.contains("LocalOnly"))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun localOnlyResidentIgnoresInvalidationForGetAndStream() = runTest {
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher { "v${++calls}" }
        }
        val key = TestKey("1")

        try {
            assertEquals("v1", store.get(key))

            store.stream(key, Freshness.LocalOnly).test {
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                store.invalidate(key)

                assertEquals("v1", store.get(key, Freshness.LocalOnly))
                expectNoEvents()
                assertEquals(1, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun dataAgeUsesInjectedWallClock() = runTest {
        val clock = FakeWallClock(now = 1_000L)
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher { "v" }
        }

        try {
            assertEquals("v", store.get(TestKey("1")))
            clock.now = 31_000L

            store.stream(TestKey("1")).test {
                val data = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals(30.seconds, data.age)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }
}
