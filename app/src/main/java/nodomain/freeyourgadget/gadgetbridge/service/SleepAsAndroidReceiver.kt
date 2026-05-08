package nodomain.freeyourgadget.gadgetbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.ble.BleManager
import timber.log.Timber
import javax.inject.Inject

/**
 * Bridges Sleep as Android events to the connected device.
 *
 * Sleep as Android sends broadcast intents at key sleep events.
 * We intercept them and command the connected gadget accordingly:
 *
 *  - SLEEP_TRACKING_STARTED: enable continuous HR, SpO2, accelerometer on the band
 *  - SLEEP_TRACKING_STOPPED: disable continuous monitoring
 *  - ALARM_ALERT_START: vibrate the band to wake the user
 *  - ALARM_ALERT_DISMISS: stop alarm vibration
 *  - ALARM_SNOOZE_CLICKED: stop vibration briefly
 *
 * Sleep as Android also supports sending actigraphic data back to it:
 * The app can send an intent with accelerometer data sampled from the band.
 *
 * @see https://docs.sleep.urbandroid.org/devs/ble-device-api.html
 */
@AndroidEntryPoint
class SleepAsAndroidReceiver : BroadcastReceiver() {

    @Inject lateinit var bleManager: BleManager
    @Inject
    lateinit var sleepAsAndroidSender: SleepAsAndroidSender

    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        // Sleep as Android broadcast actions (Generic)
        const val ACTION_SLEEP_STARTED_GEN =
            "com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STARTED"
        const val ACTION_SLEEP_STOPPED_GEN =
            "com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STOPPED"
        const val ACTION_ALARM_START_GEN = "com.urbandroid.sleep.alarmclock.ALARM_ALERT_START"
        const val ACTION_ALARM_DISMISS_GEN = "com.urbandroid.sleep.alarmclock.ALARM_ALERT_DISMISS"
        const val ACTION_SNOOZE_CLICKED_GEN = "com.urbandroid.sleep.alarmclock.ALARM_SNOOZE_CLICKED"
        const val ACTION_SNOOZE_CANCEL_GEN =
            "com.urbandroid.sleep.alarmclock.ALARM_ALERT_SNOOZE_CANCELLED"

        // Sleep as Android Wearable API actions
        const val ACTION_START_TRACKING = "com.urbandroid.sleep.watch.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.urbandroid.sleep.watch.STOP_TRACKING"
        const val ACTION_START_ALARM = "com.urbandroid.sleep.watch.START_ALARM"
        const val ACTION_STOP_ALARM = "com.urbandroid.sleep.watch.STOP_ALARM"
        const val ACTION_SET_SUSPENDED = "com.urbandroid.sleep.watch.SET_SUSPENDED"
        const val ACTION_SET_PAUSE = "com.urbandroid.sleep.watch.SET_PAUSE"
        const val ACTION_CHECK_CONNECTED = "com.urbandroid.sleep.watch.CHECK_CONNECTED"
        const val ACTION_HINT = "com.urbandroid.sleep.watch.HINT"

        // We send this intent to Sleep as Android to push live actigraph data
        const val ACTION_PUSH_ACTIGRAPH  = "com.urbandroid.sleep.watch.INTENT_WATCH_DATA"

        /** Build intent to send actigraphy batch to Sleep as Android */
        fun buildAcigraphIntent(values: FloatArray): Intent =
            Intent(ACTION_PUSH_ACTIGRAPH).apply {
                setPackage("com.urbandroid.sleep")
                putExtra("com.urbandroid.sleep.watch.DATA", values)
            }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("SleepAsAndroidReceiver: received action=${intent.action}")
        receiverScope.launch {
            when (intent.action) {
                ACTION_SLEEP_STARTED_GEN, ACTION_START_TRACKING -> {
                    bleManager.onSleepTrackingStarted()
                }

                ACTION_SLEEP_STOPPED_GEN, ACTION_STOP_TRACKING -> {
                    bleManager.onSleepTrackingStopped()
                }

                ACTION_ALARM_START_GEN, ACTION_START_ALARM -> {
                    bleManager.triggerAlarm()
                }

                ACTION_ALARM_DISMISS_GEN, ACTION_STOP_ALARM -> {
                    bleManager.dismissAlarm()
                }

                ACTION_SNOOZE_CLICKED_GEN -> {
                    bleManager.dismissAlarm()
                }

                ACTION_SNOOZE_CANCEL_GEN -> {
                    bleManager.triggerAlarm()
                }

                ACTION_SET_SUSPENDED -> {
                    val suspended = intent.getBooleanExtra("SUSPENDED", false)
                    bleManager.setRawSensorEnabled(!suspended)
                    bleManager.setHeartRateMonitoring(!suspended)
                }

                ACTION_SET_PAUSE -> {
                    val pauseTimestamp = intent.getLongExtra("TIMESTAMP", 0L)
                    val isPaused = pauseTimestamp > System.currentTimeMillis()
                    bleManager.setRawSensorEnabled(!isPaused)
                    bleManager.setHeartRateMonitoring(!isPaused)
                }

                ACTION_CHECK_CONNECTED -> {
                    sleepAsAndroidSender.confirmConnected()
                }

                ACTION_HINT -> {
                    bleManager.vibrate()
                }
            }
        }
    }
}

/**
 * Helper to send actigraphy data back to Sleep as Android.
 *
 * The band's accelerometer gives us movement data.
 * We buffer several seconds of samples then send them to Sleep as Android
 * so it can improve its sleep stage detection with wearable motion data.
 *
 * Values should be in units of "g" (gravitational acceleration).
 * Sleep as Android expects them at 1Hz sampling rate.
 */
class SleepAsAndroidBridge(private val context: Context) {

    private val actigraphBuffer = mutableListOf<Float>()

    fun addAccelerometerSample(x: Float, y: Float, z: Float) {
        // Compute vector magnitude as actigraphy value
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        actigraphBuffer.add(magnitude)

        // Send in batches of 20 samples (20 seconds at 1Hz)
        if (actigraphBuffer.size >= 20) {
            flushToSleepAsAndroid()
        }
    }

    private fun flushToSleepAsAndroid() {
        if (actigraphBuffer.isEmpty()) return
        val values = actigraphBuffer.toFloatArray()
        actigraphBuffer.clear()

        try {
            val intent = SleepAsAndroidReceiver.buildAcigraphIntent(values)
            context.sendBroadcast(intent)
            Timber.d("SleepAsAndroidBridge: sent ${values.size} actigraphy samples")
        } catch (e: Exception) {
            Timber.e(e, "SleepAsAndroidBridge: failed to send actigraphy data")
        }
    }

    fun flush() = flushToSleepAsAndroid()
}
