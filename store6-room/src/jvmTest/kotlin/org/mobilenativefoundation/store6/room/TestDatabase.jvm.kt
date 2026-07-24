package org.mobilenativefoundation.store6.room

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

internal actual fun inMemoryTestDatabaseBuilder(): RoomDatabase.Builder<Store6RoomTestDatabase> =
    Room.inMemoryDatabaseBuilder<Store6RoomTestDatabase>()

internal actual fun fileTestDatabaseBuilder(
    path: String,
): RoomDatabase.Builder<Store6RoomTestDatabase> =
    Room.databaseBuilder<Store6RoomTestDatabase>(name = path)

internal actual fun newTempDatabasePath(): String =
    File.createTempFile("store6-room-", ".db")
        .also { it.delete() }
        .absolutePath
