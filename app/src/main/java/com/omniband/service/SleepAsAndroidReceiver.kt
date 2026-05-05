package com.omniband.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omniband.ble.BleManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        // Sleep as Android broadcast actions
        const val ACTION_SLEEP_STARTED   = "com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STARTED"
        const val ACTION_SLEEP_STOPPED   = "com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STOPPED"
        const val ACTION_ALARM_START     = "com.urbandroid.sleep.alarmclock.ALARM_ALERT_START"
        const val ACTION_ALARM_DISMISS   = "com.urbandroid.sleep.alarmclock.ALARM_ALERT_DISMISS"
        const val ACTION_SNOOZE_CLICKED  = "com.urbandroid.sleep.alarmclock.ALARM_SNOOZE_CLICKED"
        const val ACTION_SNOOZE_CANCEL   = "com.urbandroid.sleep.alarmclock.ALARM_ALERT_SNOOZE_CANCELLED"

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
                ACTION_SLEEP_STARTED  -> bleManager.onSleepTrackingStarted()
                ACTION_SLEEP_STOPPED  -> bleManager.onSleepTrackingStopped()
                ACTION_ALARM_START    -> bleManager.triggerAlarm()
                ACTION_ALARM_DISMISS  -> bleManager.dismissAlarm()
                ACTION_SNOOZE_CLICKED -> {
                    bleManager.dismissAlarm()
                    // Snooze: stop alarm for now, will restart at next snooze time
                }
                ACTION_SNOOZE_CANCEL  -> bleManager.triggerAlarm()
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
