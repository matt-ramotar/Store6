package org.mobilenativefoundation.store6.room

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.random.Random
import platform.posix.getenv

internal actual fun inMemoryTestDatabaseBuilder(): RoomDatabase.Builder<Store6RoomTestDatabase> =
    Room.inMemoryDatabaseBuilder<Store6RoomTestDatabase>()

internal actual fun fileTestDatabaseBuilder(
    path: String,
): RoomDatabase.Builder<Store6RoomTestDatabase> =
    Room.databaseBuilder<Store6RoomTestDatabase>(name = path)

@OptIn(ExperimentalForeignApi::class)
internal actual fun newTempDatabasePath(): String {
    val temporaryDirectory = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"
    val randomHex = Random.nextLong().toULong().toString(radix = 16)
    return "$temporaryDirectory/store6-room-$randomHex.db"
}
