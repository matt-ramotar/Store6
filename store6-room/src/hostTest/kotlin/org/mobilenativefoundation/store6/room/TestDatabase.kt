@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "kit_rows",
    primaryKeys = ["namespace", "id"],
)
internal class KitRowEntity(
    val namespace: String,
    val id: String,
    val payload: String,
)

@Dao
internal interface KitRowDao {
    @Query("SELECT * FROM kit_rows WHERE namespace = :namespace AND id = :id")
    fun row(namespace: String, id: String): Flow<KitRowEntity?>

    @Upsert
    suspend fun upsert(row: KitRowEntity)

    @Query("DELETE FROM kit_rows WHERE namespace = :namespace AND id = :id")
    suspend fun delete(namespace: String, id: String)

    @Query("DELETE FROM kit_rows WHERE namespace = :namespace")
    suspend fun deleteNamespace(namespace: String)

    @Query("DELETE FROM kit_rows")
    suspend fun deleteAll()
}

@Database(
    entities = [
        KitRowEntity::class,
        Store6BookkeepingEntity::class,
        Store6WatermarkEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@ConstructedBy(Store6RoomTestDatabaseConstructor::class)
internal abstract class Store6RoomTestDatabase : RoomDatabase() {
    abstract fun kitRowDao(): KitRowDao

    abstract fun store6BookkeeperDao(): Store6BookkeeperDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object Store6RoomTestDatabaseConstructor :
    RoomDatabaseConstructor<Store6RoomTestDatabase> {
    override fun initialize(): Store6RoomTestDatabase
}

internal expect fun inMemoryTestDatabaseBuilder(): RoomDatabase.Builder<Store6RoomTestDatabase>

internal expect fun fileTestDatabaseBuilder(
    path: String,
): RoomDatabase.Builder<Store6RoomTestDatabase>

internal expect fun newTempDatabasePath(): String

internal fun createTestDatabase(): Store6RoomTestDatabase =
    configure(inMemoryTestDatabaseBuilder())

internal fun openTestDatabase(path: String): Store6RoomTestDatabase =
    configure(fileTestDatabaseBuilder(path))

private fun configure(
    builder: RoomDatabase.Builder<Store6RoomTestDatabase>,
): Store6RoomTestDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
