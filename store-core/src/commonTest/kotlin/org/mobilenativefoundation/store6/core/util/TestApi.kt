package org.mobilenativefoundation.store6.core.util

internal interface TestApi<Key : Any, Network : Any, Output : Any, Response : Any> {
    fun get(
        key: Key,
        fail: Boolean = false,
        ttl: Long? = null,
    ): Network?

    fun post(
        key: Key,
        value: Output,
        fail: Boolean = false,
    ): Response
}
