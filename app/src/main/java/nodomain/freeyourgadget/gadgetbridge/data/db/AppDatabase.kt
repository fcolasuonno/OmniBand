package nodomain.freeyourgadget.gadgetbridge.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.BatteryDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.DeviceDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.HeartRateDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.NotificationLogDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.SleepDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.SpO2Dao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.StepsDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.StressDao
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.BatteryEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.DeviceEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.HeartRateEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.NotificationLogEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepSessionEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepStageEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SpO2Entity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StepsEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StressEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `stress` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`deviceAddress` TEXT NOT NULL, " +
                    "`score` INTEGER NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL)"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Stress rows imported before v3 used doubled minute steps (wrong timestamps).
        // Purge them; the next history sync re-imports the window with correct times.
        db.execSQL("DELETE FROM `stress`")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Stress/sleep rows imported before v4 could carry future timestamps
        // (unreliable band fetch clocks); v4 shifts batches back to phone time.
        // Purge them so the dashboard never shows future-dated samples.
        db.execSQL("DELETE FROM `stress`")
        db.execSQL("DELETE FROM `sleep_sessions`")
        db.execSQL("DELETE FROM `sleep_stages`")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notification_log` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`packageName` TEXT NOT NULL, " +
                    "`appName` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`body` TEXT NOT NULL, " +
                    "`postTime` INTEGER NOT NULL, " +
                    "`receivedAt` INTEGER NOT NULL, " +
                    "`forwarded` INTEGER NOT NULL, " +
                    "`skipReason` TEXT)"
        )
    }
}

@Database(
    entities = [
        DeviceEntity::class,
        HeartRateEntity::class,
        StepsEntity::class,
        SleepSessionEntity::class,
        SleepStageEntity::class,
        SpO2Entity::class,
        BatteryEntity::class,
        StressEntity::class,
        NotificationLogEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun heartRateDao(): HeartRateDao
    abstract fun stepsDao(): StepsDao
    abstract fun sleepDao(): SleepDao
    abstract fun spo2Dao(): SpO2Dao
    abstract fun batteryDao(): BatteryDao
    abstract fun stressDao(): StressDao
    abstract fun notificationLogDao(): NotificationLogDao
}
