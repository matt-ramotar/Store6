package org.mobilenativefoundation.store6.core.util.fake.fallback

sealed class Page {
    data class Data(
        val title: String,
        val ttl: Long? = null,
    ) : Page()

    object Empty : Page()
}
