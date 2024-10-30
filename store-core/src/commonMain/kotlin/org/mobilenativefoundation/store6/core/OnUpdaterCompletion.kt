package org.mobilenativefoundation.store6.core

data class OnUpdaterCompletion<Response : Any>(
    val onSuccess: (UpdaterResult.Success) -> Unit,
    val onFailure: (UpdaterResult.Error) -> Unit,
)
