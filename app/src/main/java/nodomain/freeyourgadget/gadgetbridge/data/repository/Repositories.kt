package nodomain.freeyourgadget.gadgetbridge.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nodomain.freeyourgadget.gadgetbridge.ble.DeviceType
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// -----------------------------------------------------------------
// Device Repository
// -----------------------------------------------------------------

@Singleton
class DeviceRepository @Inject constructor(
    private val deviceDao: DeviceDao
) {
    val allDevices: Flow<List<DeviceEntity>> = deviceDao.getAllDevices()

    suspend fun getActiveDevice(): DeviceEntity? = deviceDao.getActiveDevice()

    suspend fun addDevice(address: String, name: String, type: DeviceType, authKey: String? = null) {
        deviceDao.insertDevice(
            DeviceEntity(
                address = address,
                name = name,
                deviceType = type.name,
                authKey = authKey
            )
        )
    }

    suspend fun setActiveDevice(address: String) {
        deviceDao.deactivateAll()
        deviceDao.setActive(address)
    }

    suspend fun updateLastConnected(address: String) {
        deviceDao.updateLastConnected(address, System.currentTimeMillis())
    }

    suspend fun removeDevice(address: String) {
        deviceDao.getDevice(address)?.let { deviceDao.deleteDevice(it) }
    }
}

// -----------------------------------------------------------------
// Health Repository
// -----------------------------------------------------------------

@Singleton
class HealthRepository @Inject constructor(
    private val heartRateDao: HeartRateDao,
    private val stepsDao: StepsDao,
    private val sleepDao: SleepDao,
    private val spo2Dao: SpO2Dao,
    private val batteryDao: BatteryDao
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // Heart Rate
    fun getRecentHeartRate(address: String): Flow<List<HeartRateEntity>> =
        heartRateDao.getRecent(address, 200)

    suspend fun saveHeartRate(address: String, bpm: Int) {
        heartRateDao.insert(HeartRateEntity(deviceAddress = address, bpm = bpm))
    }

    suspend fun getHeartRateStats(address: String, from: Long, to: Long): HeartRateStats {
        return HeartRateStats(
            avg = heartRateDao.getAverage(address, from, to)?.toInt() ?: 0,
            max = heartRateDao.getMax(address, from, to) ?: 0,
            min = heartRateDao.getMin(address, from, to) ?: 0
        )
    }

    // Steps
    fun getLast30DaysSteps(address: String): Flow<List<StepsEntity>> =
        stepsDao.getLast30Days(address)

    suspend fun saveSteps(address: String, count: Int, calories: Int, distanceMeters: Float) {
        val today = dateFormat.format(Date())
        val existing = stepsDao.getForDate(address, today)
        stepsDao.insertOrUpdate(
            StepsEntity(
                id = existing?.id ?: 0,
                deviceAddress = address,
                count = count,
                calories = calories,
                distanceMeters = distanceMeters,
                date = today
            )
        )
    }

    // Sleep
    fun getRecentSleepSessions(address: String): Flow<List<SleepSessionEntity>> =
        sleepDao.getRecentSessions(address)

    fun getSleepStages(sessionId: Long): Flow<List<SleepStageEntity>> =
        sleepDao.getStagesForSession(sessionId)

    suspend fun startSleepSession(address: String): Long =
        sleepDao.insertSession(
            SleepSessionEntity(
                deviceAddress = address,
                startTime = System.currentTimeMillis()
            )
        )

    suspend fun endSleepSession(sessionId: Long) {
        sleepDao.endSession(sessionId, System.currentTimeMillis())
    }

    suspend fun addSleepStage(sessionId: Long, stage: String) {
        sleepDao.insertStage(
            SleepStageEntity(
                sessionId = sessionId,
                stage = stage,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    // SpO2
    fun getRecentSpO2(address: String): Flow<List<SpO2Entity>> = spo2Dao.getRecent(address)

    suspend fun saveSpO2(address: String, percent: Int) {
        spo2Dao.insert(SpO2Entity(deviceAddress = address, percent = percent))
    }

    // Battery
    suspend fun getLatestBattery(address: String): BatteryEntity? = batteryDao.getLatest(address)
    fun getBatteryHistory(address: String): Flow<List<BatteryEntity>> = batteryDao.getLast48h(address)

    suspend fun saveBattery(address: String, percent: Int, charging: Boolean) {
        batteryDao.insert(
            BatteryEntity(
                deviceAddress = address,
                percent = percent,
                charging = charging
            )
        )
    }
}

data class HeartRateStats(val avg: Int, val max: Int, val min: Int)

// -----------------------------------------------------------------
// User Preferences (DataStore)
// -----------------------------------------------------------------

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "omniband_prefs")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private object Keys {
        val ACTIVE_DEVICE_ADDRESS = stringPreferencesKey("active_device_address")
        val ACTIVE_DEVICE_TYPE    = stringPreferencesKey("active_device_type")
        val AUTH_KEY              = stringPreferencesKey("auth_key")
        val AUTO_RECONNECT        = booleanPreferencesKey("auto_reconnect")
        val SLEEP_ANDROID_ENABLED = booleanPreferencesKey("sleep_android_enabled")
        val HR_CONTINUOUS         = booleanPreferencesKey("hr_continuous")
        val DAILY_GOAL_STEPS      = stringPreferencesKey("daily_goal_steps")
    }

    val activeDeviceAddress: Flow<String?> = context.dataStore.data
        .map { it[Keys.ACTIVE_DEVICE_ADDRESS] }

    val activeDeviceType: Flow<String?> = context.dataStore.data
        .map { it[Keys.ACTIVE_DEVICE_TYPE] }

    val authKey: Flow<String?> = context.dataStore.data
        .map { it[Keys.AUTH_KEY] }

    val autoReconnect: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.AUTO_RECONNECT] ?: true }

    val sleepAsAndroidEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SLEEP_ANDROID_ENABLED] ?: false }

    val hrContinuous: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.HR_CONTINUOUS] ?: false }

    val dailyStepGoal: Flow<Int> = context.dataStore.data
        .map { it[Keys.DAILY_GOAL_STEPS]?.toIntOrNull() ?: 8000 }

    suspend fun saveActiveDevice(address: String, type: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_DEVICE_ADDRESS] = address
            prefs[Keys.ACTIVE_DEVICE_TYPE] = type
        }
    }

    suspend fun saveAuthKey(key: String) {
        context.dataStore.edit { it[Keys.AUTH_KEY] = key }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun setSleepAsAndroidEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SLEEP_ANDROID_ENABLED] = enabled }
    }

    suspend fun setDailyStepGoal(steps: Int) {
        context.dataStore.edit { it[Keys.DAILY_GOAL_STEPS] = steps.toString() }
    }
}
