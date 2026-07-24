package org.mobilenativefoundation.store6.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * T1-only KMP Room code-generation spike. T2 replaces this with the published persistence model.
 */
@Entity(tableName = "t1_room_spike")
internal data class T1RoomSpikeEntity(
    @PrimaryKey val id: Int,
    val value: String,
)

/** T1-only DAO proving that Room can process commonMain declarations for host tests. */
@Dao
internal interface T1RoomSpikeDao {
    @Upsert
    suspend fun upsert(entity: T1RoomSpikeEntity)

    @Query("SELECT * FROM t1_room_spike WHERE id = :id LIMIT 1")
    suspend fun read(id: Int): T1RoomSpikeEntity?
}
