package com.kronokeep.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.data.local.dao.DocumentDao
import com.kronokeep.app.data.local.dao.MaintenanceScheduleDao
import com.kronokeep.app.data.local.dao.OdometerReadingDao
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.dao.ServiceLogDao
import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.data.local.entity.MaintenanceScheduleEntity
import com.kronokeep.app.data.local.entity.OdometerReadingEntity
import com.kronokeep.app.data.local.entity.OutboxEntity
import com.kronokeep.app.data.local.entity.ServiceLogEntity

@Database(
    entities = [
        AssetEntity::class,
        MaintenanceScheduleEntity::class,
        ServiceLogEntity::class,
        OdometerReadingEntity::class,
        DocumentEntity::class,
        OutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KronoDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun scheduleDao(): MaintenanceScheduleDao
    abstract fun serviceLogDao(): ServiceLogDao
    abstract fun odometerDao(): OdometerReadingDao
    abstract fun documentDao(): DocumentDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val NAME = "kronokeep.db"
    }
}
