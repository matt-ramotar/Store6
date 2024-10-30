package org.mobilenativefoundation.store6.core.impl

import org.mobilenativefoundation.store6.core.StoreWriteResponse

data class OnStoreWriteCompletion(
    val onSuccess: (StoreWriteResponse.Success) -> Unit,
    val onFailure: (StoreWriteResponse.Error) -> Unit,
)
