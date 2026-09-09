package nodomain.freeyourgadget.gadgetbridge.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.BatteryEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.DeviceEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.HeartRateEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepSessionEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepStageEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SpO2Entity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StepsEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StressEntity

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastConnectedAt DESC")
    fun getAllDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveDevice(): DeviceEntity?

    @Query("SELECT * FROM devices WHERE address = :address")
    suspend fun getDevice(address: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DeviceEntity)

    @Query("UPDATE devices SET lastConnectedAt = :timestamp WHERE address = :address")
    suspend fun updateLastConnected(address: String, timestamp: Long)

    @Query("UPDATE devices SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE devices SET isActive = 1 WHERE address = :address")
    suspend fun setActive(address: String)

    @Delete
    suspend fun deleteDevice(device: DeviceEntity)
}

@Dao
interface HeartRateDao {
    @Query("SELECT * FROM heart_rate WHERE deviceAddress = :address ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(address: String, limit: Int = 100): Flow<List<HeartRateEntity>>

    @Query("SELECT * FROM heart_rate WHERE deviceAddress = :address AND timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    fun getRange(address: String, from: Long, to: Long): Flow<List<HeartRateEntity>>

    @Query("SELECT AVG(bpm) FROM heart_rate WHERE deviceAddress = :address AND timestamp BETWEEN :from AND :to")
    suspend fun getAverage(address: String, from: Long, to: Long): Float?

    @Query("SELECT MAX(bpm) FROM heart_rate WHERE deviceAddress = :address AND timestamp BETWEEN :from AND :to")
    suspend fun getMax(address: String, from: Long, to: Long): Int?

    @Query("SELECT MIN(bpm) FROM heart_rate WHERE deviceAddress = :address AND timestamp BETWEEN :from AND :to")
    suspend fun getMin(address: String, from: Long, to: Long): Int?

    @Insert
    suspend fun insert(entity: HeartRateEntity)

    @Query("DELETE FROM heart_rate WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface StepsDao {
    @Query("SELECT * FROM steps WHERE deviceAddress = :address AND date = :date")
    suspend fun getForDate(address: String, date: String): StepsEntity?

    @Query("SELECT * FROM steps WHERE deviceAddress = :address ORDER BY date DESC LIMIT 30")
    fun getLast30Days(address: String): Flow<List<StepsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: StepsEntity)
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep_sessions WHERE deviceAddress = :address ORDER BY startTime DESC LIMIT 10")
    fun getRecentSessions(address: String): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: Long): SleepSessionEntity?

    @Query("SELECT * FROM sleep_sessions WHERE deviceAddress = :address AND startTime = :startTime LIMIT 1")
    suspend fun getSessionByStartTime(address: String, startTime: Long): SleepSessionEntity?

    @Query("SELECT * FROM sleep_stages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getStagesForSession(sessionId: Long): Flow<List<SleepStageEntity>>

    @Insert
    suspend fun insertSession(session: SleepSessionEntity): Long

    @Query("UPDATE sleep_sessions SET endTime = :endTime WHERE id = :id")
    suspend fun endSession(id: Long, endTime: Long)

    @Insert
    suspend fun insertStage(stage: SleepStageEntity)
}

@Dao
interface SpO2Dao {
    @Query("SELECT * FROM spo2 WHERE deviceAddress = :address ORDER BY timestamp DESC LIMIT 50")
    fun getRecent(address: String): Flow<List<SpO2Entity>>

    @Query("SELECT * FROM spo2 WHERE deviceAddress = :address ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(address: String): SpO2Entity?

    @Query("SELECT * FROM spo2 WHERE deviceAddress = :address AND timestamp = :timestamp LIMIT 1")
    suspend fun getAt(address: String, timestamp: Long): SpO2Entity?

    @Insert
    suspend fun insert(entity: SpO2Entity)
}

@Dao
interface StressDao {
    @Query("SELECT * FROM stress WHERE deviceAddress = :address ORDER BY timestamp DESC LIMIT 100")
    fun getRecent(address: String): Flow<List<StressEntity>>

    @Query("SELECT * FROM stress WHERE deviceAddress = :address ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(address: String): StressEntity?

    @Query("SELECT * FROM stress WHERE deviceAddress = :address AND timestamp = :timestamp LIMIT 1")
    suspend fun getAt(address: String, timestamp: Long): StressEntity?

    @Insert
    suspend fun insert(entity: StressEntity)
}

@Dao
interface BatteryDao {
    @Query("SELECT * FROM battery_history WHERE deviceAddress = :address ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(address: String): BatteryEntity?

    @Query("SELECT * FROM battery_history WHERE deviceAddress = :address ORDER BY timestamp DESC LIMIT 48")
    fun getLast48h(address: String): Flow<List<BatteryEntity>>

    @Insert
    suspend fun insert(entity: BatteryEntity)
}
