package nodomain.freeyourgadget.gadgetbridge.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nodomain.freeyourgadget.gadgetbridge.ble.DeviceType
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.SleepStageSample
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.BatteryDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.DeviceDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.HeartRateDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.SleepDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.SpO2Dao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.StepsDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.StressDao
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.BatteryEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.DeviceEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.HeartRateEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepSessionEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepStageEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SpO2Entity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StepsEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StressEntity
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
    private val batteryDao: BatteryDao,
    private val stressDao: StressDao
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

    /**
     * Import one historical sleep session (e.g. from a band fetch).
     * @return the new session id, or null if a session with the same start already exists.
     */
    suspend fun importSleepSession(
        address: String,
        startMs: Long,
        endMs: Long,
        stages: List<SleepStageSample>,
    ): Long? {
        if (sleepDao.getSessionByStartTime(address, startMs) != null) return null
        val id = sleepDao.insertSession(
            SleepSessionEntity(
                deviceAddress = address,
                startTime = startMs,
                endTime = endMs,
                source = "freeyourgadget.gadgetbridge"
            )
        )
        stages.forEach { st ->
            sleepDao.insertStage(
                SleepStageEntity(sessionId = id, stage = st.stage.name, timestamp = st.startMs)
            )
        }
        return id
    }

    // SpO2
    fun getRecentSpO2(address: String): Flow<List<SpO2Entity>> = spo2Dao.getRecent(address)

    suspend fun saveSpO2(address: String, percent: Int) {
        spo2Dao.insert(SpO2Entity(deviceAddress = address, percent = percent))
    }

    /** Insert one historical SpO2 sample; returns false if it already exists. */
    suspend fun importSpO2At(address: String, percent: Int, timestamp: Long): Boolean {
        if (spo2Dao.getAt(address, timestamp) != null) return false
        spo2Dao.insert(
            SpO2Entity(
                deviceAddress = address,
                percent = percent,
                timestamp = timestamp
            )
        )
        return true
    }

    fun getLatestSpO2(address: String): Flow<SpO2Entity?> =
        getRecentSpO2(address).map { list -> list.firstOrNull() }

    // Stress
    fun getRecentStress(address: String): Flow<List<StressEntity>> =
        stressDao.getRecent(address)

    /** Insert one historical stress sample; returns false if it already exists. */
    suspend fun importStressAt(address: String, score: Int, timestamp: Long): Boolean {
        if (stressDao.getAt(address, timestamp) != null) return false
        stressDao.insert(
            StressEntity(
                deviceAddress = address,
                score = score,
                timestamp = timestamp
            )
        )
        return true
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
        val DISABLE_IDLE_ALERT = booleanPreferencesKey("disable_idle_alert")

        // v2: reset the window so post-purge history (v4 DB) is fully re-pulled once.
        val LAST_HISTORY_FETCH_MS = longPreferencesKey("last_history_fetch_ms_v2")

        val NOTIF_MIRROR_ENABLED = booleanPreferencesKey("notif_mirror_enabled")
        val NOTIF_ENABLED_APPS = stringSetPreferencesKey("notif_enabled_apps")
        val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val QUIET_HOURS_START_MIN = intPreferencesKey("quiet_hours_start_min")
        val QUIET_HOURS_END_MIN = intPreferencesKey("quiet_hours_end_min")
    }

    companion object {
        /** Pinned favorites, always listed first in this order. */
        val PINNED_NOTIF_APPS = listOf(
            "com.google.android.gm", // Gmail
            "com.whatsapp",          // WhatsApp
            "com.slack"              // Slack
        )
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

    val disableIdleAlert: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.DISABLE_IDLE_ALERT] ?: false }

    val lastHistoryFetchMs: Flow<Long> = context.dataStore.data
        .map { it[Keys.LAST_HISTORY_FETCH_MS] ?: 0L }

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

    suspend fun setDisableIdleAlert(disabled: Boolean) {
        context.dataStore.edit { it[Keys.DISABLE_IDLE_ALERT] = disabled }
    }

    suspend fun setLastHistoryFetchMs(ms: Long) {
        context.dataStore.edit { it[Keys.LAST_HISTORY_FETCH_MS] = ms }
    }

    val notifMirrorEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.NOTIF_MIRROR_ENABLED] ?: false }

    /**
     * Packages allowed to mirror notifications. Defaults to the pinned favorites
     * (Gmail, WhatsApp, Slack) on first run.
     */
    val enabledNotifApps: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.NOTIF_ENABLED_APPS] ?: PINNED_NOTIF_APPS.toSet() }

    suspend fun setNotifMirrorEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIF_MIRROR_ENABLED] = enabled }
    }

    suspend fun setAppNotifEnabled(packageName: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.NOTIF_ENABLED_APPS] ?: PINNED_NOTIF_APPS.toSet()
            prefs[Keys.NOTIF_ENABLED_APPS] =
                if (enabled) current + packageName else current - packageName
        }
    }

    val quietHoursEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.QUIET_HOURS_ENABLED] ?: false }

    /** Minutes since midnight; defaults 23:30 → 07:00. */
    val quietHoursStartMin: Flow<Int> = context.dataStore.data
        .map { it[Keys.QUIET_HOURS_START_MIN] ?: (23 * 60 + 30) }

    val quietHoursEndMin: Flow<Int> = context.dataStore.data
        .map { it[Keys.QUIET_HOURS_END_MIN] ?: (7 * 60) }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.QUIET_HOURS_ENABLED] = enabled }
    }

    suspend fun setQuietHours(startMin: Int, endMin: Int) {
        context.dataStore.edit {
            it[Keys.QUIET_HOURS_START_MIN] = startMin.coerceIn(0, 1439)
            it[Keys.QUIET_HOURS_END_MIN] = endMin.coerceIn(0, 1439)
        }
    }

    /**
     * Whether [nowMin] (minutes since midnight) falls inside the quiet window.
     * Handles overnight windows where start > end (e.g. 23:30 → 07:00).
     */
    fun isQuietNow(nowMin: Int, startMin: Int, endMin: Int): Boolean =
        if (startMin <= endMin) nowMin in startMin until endMin
        else nowMin >= startMin || nowMin < endMin
}
