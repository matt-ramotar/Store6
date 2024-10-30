package org.mobilenativefoundation.store6.core.util.fake.fallback

class PagesDatabase {
    private val db: MutableMap<String, Page?> = mutableMapOf()

    fun put(
        key: String,
        input: Page,
    ): Boolean {
        db[key] = input
        return true
    }

    fun get(key: String): Page? = db[key]
}
