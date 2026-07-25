@file:OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)

package org.mobilenativefoundation.store6.compose

import androidx.compose.runtime.State
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.testing.FakeStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectAsStateWithLifecycleTest {
    private class TestKey(val id: String) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace("users")

        override fun canonicalId(): String = id
    }

    private class TestOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun collectionPausesBelowMinActiveStateAndCatchesUpOnReentry(): TestResult {
        val store = FakeStore<TestKey, String>()
        val key = TestKey("1")
        store.setValue(key, "v1")
        val owner = TestOwner().apply { moveTo(Lifecycle.State.STARTED) }
        lateinit var state: State<StoreResult<String>>
        return runComposeTest(content = {
            state = store.collectAsStateWithLifecycle(key, lifecycleOwner = owner)
        }) { host ->
            host.awaitUntil { (state.value as? StoreResult.Data<String>)?.value == "v1" }
            owner.moveTo(Lifecycle.State.CREATED)
            host.advanceFrame()
            store.setValue(key, "v2")
            host.advanceFrame()
            host.advanceFrame()
            assertEquals("v1", (state.value as StoreResult.Data<String>).value) // retained, not reset
            owner.moveTo(Lifecycle.State.STARTED)
            host.awaitUntil { (state.value as? StoreResult.Data<String>)?.value == "v2" }
        }
    }
}
