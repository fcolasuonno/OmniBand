package nodomain.freeyourgadget.gadgetbridge.service

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends health and movement data from the wearable back to Sleep as Android.
 *
 * This allows Sleep as Android to use the high-frequency accelerometer and
 * heart rate data from the band for more accurate sleep stage detection.
 */
@Singleton
class SleepAsAndroidSender @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val PACKAGE_SLEEP_AS_ANDROID = "com.urbandroid.sleep"

        // Sleep as Android wearable API actions
        private const val ACTION_WATCH_DATA_UPDATE = "com.urbandroid.sleep.watch.DATA_UPDATE"
        private const val ACTION_WATCH_HR_UPDATE = "com.urbandroid.sleep.watch.HR_DATA_UPDATE"
        private const val ACTION_WATCH_SPO2_UPDATE = "com.urbandroid.sleep.watch.SPO2_DATA_UPDATE"
        private const val ACTION_WATCH_PAUSE_FROM_WATCH =
            "com.urbandroid.sleep.watch.PAUSE_FROM_WATCH"
        private const val ACTION_WATCH_RESUME_FROM_WATCH =
            "com.urbandroid.sleep.watch.RESUME_FROM_WATCH"
        private const val ACTION_WATCH_SNOOZE_FROM_WATCH =
            "com.urbandroid.sleep.watch.SNOOZE_FROM_WATCH"
        private const val ACTION_WATCH_DISMISS_FROM_WATCH =
            "com.urbandroid.sleep.watch.DISMISS_FROM_WATCH"
        private const val ACTION_CONFIRM_CONNECTED = "com.urbandroid.sleep.watch.CONFIRM_CONNECTED"

        // Extras
        private const val EXTRA_MAX_RAW_DATA = "MAX_RAW_DATA"
        private const val EXTRA_DATA = "DATA"
    }

    private val hrBuffer = mutableListOf<Float>()
    private val movementBuffer = mutableListOf<Float>()

    private var lastHrBatchSentTime = 0L

    /**
     * Pushes a heart rate value to Sleep as Android.
     * We buffer values and send them in batches to reduce broadcast overhead.
     */
    fun onHeartRateChanged(bpm: Int) {
        hrBuffer.add(bpm.toFloat())

        // Send HR batch every 10 seconds or when buffer gets large
        val now = System.currentTimeMillis()
        if (now - lastHrBatchSentTime >= 10_000 || hrBuffer.size >= 10) {
            sendHrBatch()
            lastHrBatchSentTime = now
        }
    }

    /**
     * Pushes an accelerometer magnitude value.
     * Sleep as Android expects "max raw data" (peak movement magnitude)
     * sampled at roughly 1Hz.
     */
    fun onMovementData(magnitude: Float) {
        movementBuffer.add(magnitude)

        // Movement data is typically sent in batches of 10-12 samples
        if (movementBuffer.size >= 12) {
            sendMovementBatch()
        }
    }

    private fun sendHrBatch() {
        if (hrBuffer.isEmpty()) return
        val batch = hrBuffer.toFloatArray()
        hrBuffer.clear()

        val intent = Intent(ACTION_WATCH_HR_UPDATE).apply {
            setPackage(PACKAGE_SLEEP_AS_ANDROID)
            putExtra(EXTRA_DATA, batch)
        }
        context.sendBroadcast(intent)
        Timber.v("SleepAsAndroidSender: sent HR batch size=${batch.size}")
    }

    private fun sendMovementBatch() {
        if (movementBuffer.isEmpty()) return
        val batch = movementBuffer.toFloatArray()
        movementBuffer.clear()

        val intent = Intent(ACTION_WATCH_DATA_UPDATE).apply {
            setPackage(PACKAGE_SLEEP_AS_ANDROID)
            putExtra(EXTRA_MAX_RAW_DATA, batch)
        }
        context.sendBroadcast(intent)
        Timber.v("SleepAsAndroidSender: sent movement batch size=${batch.size}")
    }

    fun sendSnooze() {
        broadcast(ACTION_WATCH_SNOOZE_FROM_WATCH)
    }

    fun sendDismiss() {
        broadcast(ACTION_WATCH_DISMISS_FROM_WATCH)
    }

    fun sendPause() {
        broadcast(ACTION_WATCH_PAUSE_FROM_WATCH)
    }

    fun sendResume() {
        broadcast(ACTION_WATCH_RESUME_FROM_WATCH)
    }

    fun confirmConnected() {
        broadcast(ACTION_CONFIRM_CONNECTED)
    }

    private fun broadcast(action: String) {
        val intent = Intent(action).setPackage(PACKAGE_SLEEP_AS_ANDROID)
        context.sendBroadcast(intent)
        Timber.i("SleepAsAndroidSender: broadcasted $action")
    }
}
