package org.mobilenativefoundation.store6.core

class TestKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("test")
    override fun canonicalId(): String = id
}
