package nodomain.freeyourgadget.gadgetbridge.service

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import nodomain.freeyourgadget.gadgetbridge.service.SleepAsAndroidSender.Companion.ACTION_CONFIRM
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends wearable health data to Sleep as Android.
 *
 * ## Sleep as Android BLE Companion API
 *
 * Sleep as Android communicates with companion apps using directed broadcasts.  The full
 * API spec is at https://docs.sleep.urbandroid.org/devs/ble-device-api.html
 *
 * ### Data the companion app sends to Sleep as Android
 *
 * | Broadcast action                              | Extra key      | Type       | Description               |
 * |-----------------------------------------------|----------------|------------|---------------------------|
 * | `com.urbandroid.sleep.watch.DATA_UPDATE`      | `MAX_RAW_DATA` | FloatArray | Actigraphy magnitudes (1Hz)|
 * | `com.urbandroid.sleep.watch.HR_DATA_UPDATE`   | `DATA`         | FloatArray | Heart-rate samples (BPM)  |
 * | `com.urbandroid.sleep.watch.CONFIRM_CONNECTED`| `MAX_RAW_DATA` | Int        | **Required** batch size   |
 *
 * ### Actigraphy note for Mi Band 7
 * The Mi Band 7 / ZeppOS does not expose raw accelerometer data over the chunked BLE
 * protocol, so actigraphy (`DATA_UPDATE`) will never be sent.  Sleep as Android will fall
 * back to HR-only sleep-stage detection, which is less accurate but still functional.
 * Future firmware or protocol updates may enable raw sensor access.
 */
@Singleton
class SleepAsAndroidSender @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val PACKAGE_SLEEP = "com.urbandroid.sleep"

        // Outgoing action constants (companion → Sleep as Android)
        private const val ACTION_DATA_UPDATE = "com.urbandroid.sleep.watch.DATA_UPDATE"
        private const val ACTION_HR_UPDATE = "com.urbandroid.sleep.watch.HR_DATA_UPDATE"
        private const val ACTION_CONFIRM = "com.urbandroid.sleep.watch.CONFIRM_CONNECTED"
        private const val ACTION_PAUSE = "com.urbandroid.sleep.watch.PAUSE_FROM_WATCH"
        private const val ACTION_RESUME = "com.urbandroid.sleep.watch.RESUME_FROM_WATCH"
        private const val ACTION_SNOOZE = "com.urbandroid.sleep.watch.SNOOZE_FROM_WATCH"
        private const val ACTION_DISMISS = "com.urbandroid.sleep.watch.DISMISS_FROM_WATCH"

        /** Extra key for actigraphy data batches (FloatArray) and CONFIRM_CONNECTED batch size (Int). */
        private const val EXTRA_MAX_RAW_DATA = "MAX_RAW_DATA"

        /** Extra key for HR data batches. */
        private const val EXTRA_DATA = "DATA"

        /**
         * Number of actigraphy samples per batch.
         * Sent with [ACTION_CONFIRM] so Sleep as Android knows when to expect a flush.
         * Also controls the size of the local movement buffer before it is flushed.
         */
        private const val ACTIGRAPHY_BATCH_SIZE = 20

        /** Minimum interval between HR batch flushes (10 seconds). */
        private const val HR_BATCH_INTERVAL_MS = 10_000L
    }

    // Guarded by `this` — accessed from IO coroutines handling device events
    private val hrBuffer = mutableListOf<Float>()
    private val movementBuffer = mutableListOf<Float>()
    private var lastHrFlushMs = 0L

    /**
     * Buffer a heart-rate sample and flush the batch to Sleep as Android every 10 seconds
     * or when the buffer reaches 10 entries.
     */
    fun onHeartRateChanged(bpm: Int) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            hrBuffer.add(bpm.toFloat())
            if (now - lastHrFlushMs >= HR_BATCH_INTERVAL_MS || hrBuffer.size >= 10) {
                flushHrBatch()
                lastHrFlushMs = now
            }
        }
    }

    /**
     * Buffer an actigraphy magnitude value and flush when the batch is full.
     *
     * @param magnitude Vector magnitude of the accelerometer reading (units of g).
     *
     * **Note:** The Mi Band 7 does not expose raw accelerometer data, so this method is
     * never called for that device.  It is provided for future protocol support.
     */
    fun onMovementData(magnitude: Float) {
        synchronized(this) {
            movementBuffer.add(magnitude)
            if (movementBuffer.size >= ACTIGRAPHY_BATCH_SIZE) {
                flushMovementBatch()
            }
        }
    }

    // ── Flush helpers (must be called under `synchronized(this)`) ─────────────

    private fun flushHrBatch() {
        if (hrBuffer.isEmpty()) return
        val batch = hrBuffer.toFloatArray()
        hrBuffer.clear()

        val intent = Intent(ACTION_HR_UPDATE).apply {
            setPackage(PACKAGE_SLEEP)
            putExtra(EXTRA_DATA, batch)
        }
        context.sendBroadcast(intent)
        Timber.v("SleepAsAndroidSender: sent HR batch (size=%d)", batch.size)
    }

    private fun flushMovementBatch() {
        if (movementBuffer.isEmpty()) return
        val batch = movementBuffer.toFloatArray()
        movementBuffer.clear()

        val intent = Intent(ACTION_DATA_UPDATE).apply {
            setPackage(PACKAGE_SLEEP)
            putExtra(EXTRA_MAX_RAW_DATA, batch)
        }
        context.sendBroadcast(intent)
        Timber.v("SleepAsAndroidSender: sent actigraphy batch (size=%d)", batch.size)
    }

    // ── Watch control → Sleep as Android ──────────────────────────────────────

    fun sendSnooze() = broadcast(ACTION_SNOOZE)
    fun sendDismiss() = broadcast(ACTION_DISMISS)
    fun sendPause() = broadcast(ACTION_PAUSE)
    fun sendResume() = broadcast(ACTION_RESUME)

    /**
     * Respond to a [CHECK_CONNECTED][SleepAsAndroidReceiver.ACTION_CHECK_CONNECTED] ping.
     *
     * **Bug fix:** The `CONFIRM_CONNECTED` broadcast *must* include the `MAX_RAW_DATA`
     * integer extra to declare the actigraphy batch size.  Without this extra Sleep as
     * Android accepts the connection but does not request tracking data, leaving the
     * integration silently broken.
     */
    fun confirmConnected() {
        val intent = Intent(ACTION_CONFIRM).apply {
            setPackage(PACKAGE_SLEEP)
            putExtra(EXTRA_MAX_RAW_DATA, ACTIGRAPHY_BATCH_SIZE)
        }
        context.sendBroadcast(intent)
        Timber.i(
            "SleepAsAndroidSender: sent CONFIRM_CONNECTED (batchSize=%d)",
            ACTIGRAPHY_BATCH_SIZE
        )
    }

    private fun broadcast(action: String) {
        context.sendBroadcast(Intent(action).setPackage(PACKAGE_SLEEP))
        Timber.i("SleepAsAndroidSender: → %s", action)
    }
}
