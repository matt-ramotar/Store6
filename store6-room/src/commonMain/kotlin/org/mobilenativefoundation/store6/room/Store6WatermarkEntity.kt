package org.mobilenativefoundation.store6.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Adapter-owned TD-6 watermark row.
 *
 * See [Store6BookkeepingEntity] for the required database inclusion and migration rules.
 */
@ExperimentalStoreApi
@Entity(tableName = "store6_watermarks")
public class Store6WatermarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "scope")
    public val scope: String,
    @ColumnInfo(name = "sequence")
    public val sequence: Long,
)
