package com.omniband.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.omniband.data.db.dao.*
import com.omniband.data.db.entity.*

@Database(
    entities = [
        DeviceEntity::class,
        HeartRateEntity::class,
        StepsEntity::class,
        SleepSessionEntity::class,
        SleepStageEntity::class,
        SpO2Entity::class,
        BatteryEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun heartRateDao(): HeartRateDao
    abstract fun stepsDao(): StepsDao
    abstract fun sleepDao(): SleepDao
    abstract fun spo2Dao(): SpO2Dao
    abstract fun batteryDao(): BatteryDao
}
