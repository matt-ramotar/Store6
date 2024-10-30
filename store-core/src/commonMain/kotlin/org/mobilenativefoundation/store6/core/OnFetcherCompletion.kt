package org.mobilenativefoundation.store6.core

data class OnFetcherCompletion<Network : Any>(
    val onSuccess: (FetcherResult.Data<Network>) -> Unit,
    val onFailure: (FetcherResult.Error) -> Unit,
)
