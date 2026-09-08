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
 * Bridges Sleep as Android broadcast events to the connected wearable device.
 *
 * Sleep as Android sends directed broadcasts at key sleep lifecycle moments.  This receiver
 * intercepts them and forwards the appropriate command to the band via [BleManager].
 *
 * ## Incoming broadcasts (Sleep as Android → OmniBand)
 *
 * ### Wearable API actions (`com.urbandroid.sleep.watch.*`)
 * These are the preferred, explicit-companion-app actions documented at
 * https://docs.sleep.urbandroid.org/devs/ble-device-api.html
 *
 * | Action                  | Effect                                              |
 * |-------------------------|-----------------------------------------------------|
 * | `START_TRACKING`        | Enable continuous HR + SpO₂ on the band             |
 * | `STOP_TRACKING`         | Disable continuous monitoring                       |
 * | `START_ALARM`           | Trigger alarm vibration on the band                 |
 * | `STOP_ALARM`            | Stop alarm vibration                                |
 * | `SET_SUSPENDED`         | Suspend/resume sensor streaming                     |
 * | `SET_PAUSE`             | Pause/resume sensor streaming based on timestamp    |
 * | `CHECK_CONNECTED`       | Reply with `CONFIRM_CONNECTED` broadcast            |
 * | `HINT`                  | Short vibration hint on the band                   |
 *
 * ### Generic alarm-clock actions (`com.urbandroid.sleep.alarmclock.*`)
 * Older broadcasts still used by some Sleep as Android versions.  They are handled
 * identically to their Wearable API equivalents above.
 *
 * ## Reply broadcasts (OmniBand → Sleep as Android)
 * Sent via [SleepAsAndroidSender]:
 * - `CONFIRM_CONNECTED` — with `MAX_RAW_DATA` integer extra (required by the API)
 * - `HR_DATA_UPDATE` — heart-rate samples (FloatArray) via `DATA` extra
 * - `DATA_UPDATE` — actigraphy magnitudes (FloatArray) via `MAX_RAW_DATA` extra
 *   *(not sent for Mi Band 7 — no raw accelerometer access in ZeppOS protocol)*
 */
@AndroidEntryPoint
class SleepAsAndroidReceiver : BroadcastReceiver() {

    @Inject lateinit var bleManager: BleManager
    @Inject
    lateinit var sleepAsAndroidSender: SleepAsAndroidSender

    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        // ── Wearable API actions ───────────────────────────────────────────────
        const val ACTION_START_TRACKING = "com.urbandroid.sleep.watch.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.urbandroid.sleep.watch.STOP_TRACKING"
        const val ACTION_START_ALARM = "com.urbandroid.sleep.watch.START_ALARM"
        const val ACTION_STOP_ALARM = "com.urbandroid.sleep.watch.STOP_ALARM"
        const val ACTION_SET_SUSPENDED = "com.urbandroid.sleep.watch.SET_SUSPENDED"
        const val ACTION_SET_PAUSE = "com.urbandroid.sleep.watch.SET_PAUSE"
        const val ACTION_CHECK_CONNECTED = "com.urbandroid.sleep.watch.CHECK_CONNECTED"
        const val ACTION_HINT = "com.urbandroid.sleep.watch.HINT"

        // ── Generic alarm-clock actions (older Sleep as Android versions) ──────
        const val ACTION_SLEEP_STARTED = "com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STARTED"
        const val ACTION_SLEEP_STOPPED = "com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STOPPED"
        const val ACTION_ALARM_START = "com.urbandroid.sleep.alarmclock.ALARM_ALERT_START"
        const val ACTION_ALARM_DISMISS = "com.urbandroid.sleep.alarmclock.ALARM_ALERT_DISMISS"
        const val ACTION_SNOOZE_CLICKED = "com.urbandroid.sleep.alarmclock.ALARM_SNOOZE_CLICKED"
        const val ACTION_SNOOZE_CANCEL =
            "com.urbandroid.sleep.alarmclock.ALARM_ALERT_SNOOZE_CANCELLED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Timber.i("SleepAsAndroidReceiver: action=%s", action)

        receiverScope.launch {
            when (action) {
                ACTION_START_TRACKING, ACTION_SLEEP_STARTED -> {
                    bleManager.onSleepTrackingStarted()
                }

                ACTION_STOP_TRACKING, ACTION_SLEEP_STOPPED -> {
                    bleManager.onSleepTrackingStopped()
                }

                ACTION_START_ALARM, ACTION_ALARM_START -> {
                    bleManager.triggerAlarm()
                }

                ACTION_STOP_ALARM, ACTION_ALARM_DISMISS -> {
                    bleManager.dismissAlarm()
                }

                ACTION_SNOOZE_CLICKED -> {
                    // Snooze = stop current alarm vibration
                    bleManager.dismissAlarm()
                }

                ACTION_SNOOZE_CANCEL -> {
                    // Snooze cancelled = resume alarm vibration
                    bleManager.triggerAlarm()
                }

                ACTION_SET_SUSPENDED -> {
                    val suspended = intent.getBooleanExtra("SUSPENDED", false)
                    bleManager.setRawSensorEnabled(!suspended)
                    bleManager.setHeartRateMonitoring(!suspended)
                }

                ACTION_SET_PAUSE -> {
                    // TIMESTAMP is the epoch millis when the pause ends (0 = not paused)
                    val pauseUntil = intent.getLongExtra("TIMESTAMP", 0L)
                    val paused = pauseUntil > System.currentTimeMillis()
                    bleManager.setRawSensorEnabled(!paused)
                    bleManager.setHeartRateMonitoring(!paused)
                }

                ACTION_CHECK_CONNECTED -> {
                    // Reply with CONFIRM_CONNECTED including the required MAX_RAW_DATA extra.
                    // Without this extra Sleep as Android connects but never requests data.
                    sleepAsAndroidSender.confirmConnected()
                }

                ACTION_HINT -> {
                    // Short vibration hint from the Sleep as Android UI
                    bleManager.vibrate()
                }
            }
        }
    }
}
