package nodomain.freeyourgadget.gadgetbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.ble.BleManager
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.VibratePattern
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
 * https://sleep.urbandroid.org/docs/devs/wearable_api.html
 *
 * | Action                  | Effect                                              |
 * |-------------------------|-----------------------------------------------------|
 * | `START_TRACKING`        | Enable continuous HR + SpO₂ on the band             |
 * | `STOP_TRACKING`         | Disable continuous monitoring                       |
 * | `START_ALARM`           | Trigger alarm vibration (honours `DELAY`; `-1` = skip) |
 * | `STOP_ALARM`            | Stop alarm vibration (cancels a pending delayed one)|
 * | `SET_SUSPENDED`         | Suspend/resume sensor streaming                     |
 * | `SET_PAUSE`             | Pause/resume sensor streaming based on timestamp    |
 * | `SET_BATCH_SIZE`        | Desired actigraphy batch (`SIZE`) — logged, no-op   |
 * | `CHECK_CONNECTED`       | Reply with `CONFIRM_CONNECTED` broadcast            |
 * | `HINT`                  | Vibration hint (`REPEAT` > 1 → double pattern)      |
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

    /** Pending delayed alarm vibration (START_ALARM with DELAY > 0); cancelled by STOP_ALARM. */
    private var pendingAlarmJob: Job? = null

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
        const val ACTION_SET_BATCH_SIZE = "com.urbandroid.sleep.watch.SET_BATCH_SIZE"

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
                    // Spec extras (logged; HR streaming stays on for the dashboard regardless):
                    // DO_HR_MONITORING enables HR/HRV, DO_OXIMETER_MONITORING enables SpO2.
                    Timber.d(
                        "SleepAsAndroidReceiver: START_TRACKING doHr=%s doSpo2=%s",
                        intent.getBooleanExtra("DO_HR_MONITORING", true),
                        intent.getBooleanExtra("DO_OXIMETER_MONITORING", false)
                    )
                    bleManager.onSleepTrackingStarted()
                    sleepAsAndroidSender.startMovementTicker()
                }

                ACTION_STOP_TRACKING, ACTION_SLEEP_STOPPED -> {
                    bleManager.onSleepTrackingStopped()
                    sleepAsAndroidSender.stopMovementTicker()
                }

                ACTION_START_ALARM, ACTION_ALARM_START -> {
                    // DELAY (int ms): vibrate after this delay; -1 = wearable alarm
                    // disabled in Sleep as Android → must NOT vibrate.
                    val delayMs = intent.getIntExtra("DELAY", 0)
                    pendingAlarmJob?.cancel()
                    pendingAlarmJob = null
                    when {
                        delayMs == -1 -> Timber.i(
                            "SleepAsAndroidReceiver: START_ALARM with DELAY=-1 (disabled) — ignoring"
                        )

                        delayMs > 0 -> {
                            Timber.i(
                                "SleepAsAndroidReceiver: START_ALARM in %d ms",
                                delayMs
                            )
                            pendingAlarmJob = receiverScope.launch {
                                delay(delayMs.toLong())
                                bleManager.triggerAlarm()
                            }
                        }

                        else -> bleManager.triggerAlarm()
                    }
                }

                ACTION_STOP_ALARM, ACTION_ALARM_DISMISS -> {
                    pendingAlarmJob?.cancel()
                    pendingAlarmJob = null
                    bleManager.dismissAlarm()
                }

                ACTION_SNOOZE_CLICKED -> {
                    // Snooze = stop current alarm vibration
                    pendingAlarmJob?.cancel()
                    pendingAlarmJob = null
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
                    // REPEAT: how many times the hint should repeat — map to vibration pattern.
                    val repeat = intent.getIntExtra("REPEAT", 1)
                    bleManager.vibrate(if (repeat > 1) VibratePattern.DOUBLE else VibratePattern.SHORT)
                }

                ACTION_SET_BATCH_SIZE -> {
                    // SIZE (long): desired actigraphy batch size — honored by the sender.
                    sleepAsAndroidSender.setActigraphyBatchSize(intent.getLongExtra("SIZE", 20L))
                }
            }
        }
    }
}
