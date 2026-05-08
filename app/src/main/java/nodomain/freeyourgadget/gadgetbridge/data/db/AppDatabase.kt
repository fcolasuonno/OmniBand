package nodomain.freeyourgadget.gadgetbridge.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.BatteryDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.DeviceDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.HeartRateDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.SleepDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.SpO2Dao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.StepsDao
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.BatteryEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.DeviceEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.HeartRateEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepSessionEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepStageEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SpO2Entity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StepsEntity

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
