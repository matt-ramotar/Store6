@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight

import org.mobilenativefoundation.store6.core.EmissionSequenceConformanceTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.FreshnessPolicyConformanceTest
import org.mobilenativefoundation.store6.core.SingleFlightConformanceTest
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.StoreConformanceTest
import org.mobilenativefoundation.store6.core.StoreInvalidationConformanceTest
import org.mobilenativefoundation.store6.core.StoreInvalidationStressConformance
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreRevalidationConformance
import kotlin.coroutines.EmptyCoroutineContext

class StoreConformanceAgainstSqlDelightSotTest : StoreConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installBorrowedSqlDelightSot()
    }
}

class StoreInvalidationConformanceAgainstSqlDelightSotTest : StoreInvalidationConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installBorrowedSqlDelightSot()
    }
}

class EmissionSequenceConformanceAgainstSqlDelightSotTest : EmissionSequenceConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installBorrowedSqlDelightSot()
    }
}

class SingleFlightConformanceAgainstSqlDelightSotTest : SingleFlightConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installBorrowedSqlDelightSot()
    }
}

class FreshnessPolicyConformanceAgainstSqlDelightSotTest : FreshnessPolicyConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installBorrowedSqlDelightSot()
    }
}

class StoreRevalidationConformanceAgainstSqlDelightSotTest : StoreRevalidationConformance() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installBorrowedSqlDelightSot()
    }
}

class StoreInvalidationStressAgainstSqlDelightSotTest : StoreInvalidationStressConformance() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.installBorrowedSqlDelightSot()
    }
}

private fun <K : StoreKey, V : Any> StoreBuilder<K, V>.installBorrowedSqlDelightSot() {
    // The byte-identical borrowed scenarios establish enrollment and publication with their own
    // observable causal barriers. An extra test-fixture dispatcher hop can move the SQL read past
    // those barriers under a saturated full-root build. Keep the real driver on the caller context
    // for this borrowed suite only; production and adapter-owned fixtures retain Dispatchers.Default.
    persistence(
        sqlDelightTestSot(
            harness = freshHarness(),
            readContext = EmptyCoroutineContext,
        ),
    )
}
