@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.ReceiveTurbine
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult

internal class MutationsTestKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("mutations")

    override fun canonicalId(): String = id
}

internal suspend fun ReceiveTurbine<StoreResult<String>>.awaitData(): StoreResult.Data<String> {
    while (true) {
        when (val result = awaitItem()) {
            is StoreResult.Data -> return result
            is StoreResult.Error,
            is StoreResult.Loading,
            is StoreResult.Revalidated,
            -> Unit
        }
    }
}

internal suspend fun ReceiveTurbine<StoreResult<String>>.awaitConfirmed(): StoreResult.Data<String> {
    while (true) {
        val result = awaitData()
        if (result.origin != Origin.OVERLAY) return result
    }
}
