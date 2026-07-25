@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.compose

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class StoreResultMutationPolicyTest {
    private val policy = storeResultMutationPolicy<String>()
    private fun data(value: String, refreshing: Boolean = false, age: Long = 0) =
        TestStoreResults.data(
            value = value, origin = Origin.FETCHER, age = age.milliseconds,
            isStale = false, refreshing = refreshing,
        )

    @Test fun equivalentForStructurallyEqualDataIgnoringAge() =
        assertTrue(policy.equivalent(data("a", age = 0), data("a", age = 90)))

    @Test fun notEquivalentWhenFlagsDiffer() =
        assertFalse(policy.equivalent(data("a"), data("a", refreshing = true)))

    @Test fun notEquivalentAcrossVariants() =
        assertFalse(policy.equivalent(data("a"), TestStoreResults.loading()))

    @Test fun distinctErrorInstancesAreNotEquivalent() {
        val e1 = TestStoreResults.error(TestStoreResults.fetchError("x"), servedStale = false)
        val e2 = TestStoreResults.error(TestStoreResults.fetchError("x"), servedStale = false)
        assertFalse(policy.equivalent(e1, e2))
    }

    @Test fun sameInstanceIsEquivalent() {
        val loading = TestStoreResults.loading()
        assertTrue(policy.equivalent(loading, loading))
    }
}
