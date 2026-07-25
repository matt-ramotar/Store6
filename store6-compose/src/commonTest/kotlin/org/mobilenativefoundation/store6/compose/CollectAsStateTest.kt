@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.compose

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestResult
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.testing.FakeStore
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

class CollectAsStateTest {
    private class TestKey(val id: String) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace("users")

        override fun canonicalId(): String = id
    }

    @Test
    fun firstCompositionReportsLoadingThenScriptedData(): TestResult {
        val store = FakeStore<TestKey, String>()
        val key = TestKey("1")
        store.enqueueFetchValue(key, "alice")
        val observed = mutableListOf<StoreResult<String>>()
        lateinit var state: State<StoreResult<String>>
        return runComposeTest(content = {
            state = store.collectAsState(key)
            observed += state.value
        }) { host ->
            assertIs<StoreResult.Loading>(observed.first())
            host.awaitUntil { state.value is StoreResult.Data<*> }
            assertEquals("alice", (state.value as StoreResult.Data<String>).value)
        }
    }

    @Test
    fun structurallyEqualDataFramesDoNotRecompose(): TestResult {
        val flow = MutableSharedFlow<StoreResult<String>>(replay = 1)
        var recompositions = 0
        // What the composition actually READ, as opposed to what the State already holds: a
        // State write lands a frame before the recomposition that observes it, so counting
        // recompositions against the raw State would sample a stale baseline.
        var rendered: StoreResult<String>? = null
        fun data(value: String, ageMillis: Long) = TestStoreResults.data(
            value = value, origin = Origin.FETCHER, age = ageMillis.milliseconds,
            isStale = false, refreshing = false,
        )
        return runComposeTest(content = {
            val state = flow.collectAsStoreState()
            recompositions += 1
            rendered = state.value
        }) { host ->
            flow.emit(data("alice", 0))
            host.awaitUntil { (rendered as? StoreResult.Data<String>)?.value == "alice" }
            host.advanceFrame() // drain any frame still in flight before sampling
            val baseline = recompositions
            flow.emit(data("alice", 40)) // new instance, equal sans age
            host.advanceFrame()
            host.advanceFrame()
            assertEquals(baseline, recompositions)
            flow.emit(data("bob", 80))
            host.awaitUntil { (rendered as? StoreResult.Data<String>)?.value == "bob" }
            assertEquals(baseline + 1, recompositions)
        }
    }

    @Test
    fun lifecycleFramesAlwaysRecompose(): TestResult {
        val flow = MutableSharedFlow<StoreResult<String>>(replay = 1)
        var recompositions = 0
        var rendered: StoreResult<String>? = null
        return runComposeTest(content = {
            val state = flow.collectAsStoreState()
            recompositions += 1
            rendered = state.value
        }) { host ->
            flow.emit(TestStoreResults.error(TestStoreResults.fetchError("boom"), servedStale = true))
            host.awaitUntil { rendered is StoreResult.Error }
            host.advanceFrame() // drain any frame still in flight before sampling
            val afterFirst = recompositions
            val firstRendered = rendered
            // A distinct but structurally identical Error instance must still land: lifecycle
            // results are never merged, only Data duplicates are dropped.
            flow.emit(TestStoreResults.error(TestStoreResults.fetchError("boom"), servedStale = true))
            host.awaitUntil { rendered !== firstRendered }
            assertEquals(afterFirst + 1, recompositions)
        }
    }

    @Test
    fun equalKeyInstanceDoesNotRestartAndNewKeyDoes(): TestResult {
        val store = FakeStore<TestKey, String>()
        store.setValue(TestKey("1"), "alice")
        store.setValue(TestKey("2"), "bob")
        val currentKey = mutableStateOf(TestKey("1"))
        lateinit var state: State<StoreResult<String>>
        return runComposeTest(content = {
            val key = currentKey.value
            state = store.collectAsState(key)
        }) { host ->
            host.awaitUntil { (state.value as? StoreResult.Data<String>)?.value == "alice" }
            val interactionsAfterFirst = store.interactions.size
            currentKey.value = TestKey("1") // new instance, same identity
            host.advanceFrame()
            host.advanceFrame()
            assertEquals(interactionsAfterFirst, store.interactions.size)
            assertEquals("alice", (state.value as StoreResult.Data<String>).value)
            currentKey.value = TestKey("2")
            host.awaitUntil { (state.value as? StoreResult.Data<String>)?.value == "bob" }
        }
    }
}
