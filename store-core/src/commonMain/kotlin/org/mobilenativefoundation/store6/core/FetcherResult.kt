package org.mobilenativefoundation.store6.core

sealed class FetcherResult<out Network : Any> {
    data class Data<Network : Any>(val value: Network, val origin: String? = null) : FetcherResult<Network>()

    sealed class Error : FetcherResult<Nothing>() {
        data class Exception(val error: Throwable) : Error()

        data class Message(val message: String) : Error()

        data class Custom<E : Any>(val error: E) : Error()
    }
}
