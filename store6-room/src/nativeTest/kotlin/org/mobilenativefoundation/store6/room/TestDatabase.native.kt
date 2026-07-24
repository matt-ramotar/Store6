package org.mobilenativefoundation.store6.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

internal actual fun createTestDatabase(): TestDatabase =
    Room.databaseBuilder<TestDatabase>(name = testDatabasePath())
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()

private fun testDatabasePath(): String = "/tmp/store6-room-kmp-spike.db"
