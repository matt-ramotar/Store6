package org.mobilenativefoundation.store6.room

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [T1RoomSpikeEntity::class],
    version = 1,
    exportSchema = false,
)
@ConstructedBy(T1RoomTestDatabaseConstructor::class)
internal abstract class TestDatabase : RoomDatabase() {
    abstract fun spikeDao(): T1RoomSpikeDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object T1RoomTestDatabaseConstructor : RoomDatabaseConstructor<TestDatabase> {
    override fun initialize(): TestDatabase
}

internal expect fun createTestDatabase(): TestDatabase
