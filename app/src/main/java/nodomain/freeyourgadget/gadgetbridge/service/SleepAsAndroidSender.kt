package nodomain.freeyourgadget.gadgetbridge.service

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.service.SleepAsAndroidSender.Companion.ACTION_CONFIRM
import nodomain.freeyourgadget.gadgetbridge.service.SleepAsAndroidSender.Companion.STILLNESS_BASELINE_MS2
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends wearable health data to Sleep as Android.
 *
 * ## Sleep as Android BLE Companion API
 *
 * Sleep as Android communicates with companion apps using directed broadcasts.  The full
 * API spec is at https://sleep.urbandroid.org/docs/devs/wearable_api.html
 *
 * ### Data the companion app sends to Sleep as Android
 *
 * | Broadcast action                              | Extra key      | Type       | Description               |
 * |-----------------------------------------------|----------------|------------|---------------------------|
 * | `com.urbandroid.sleep.watch.DATA_UPDATE`      | `MAX_RAW_DATA` | FloatArray | Actigraphy magnitudes (1Hz)|
 * | `com.urbandroid.sleep.watch.HR_DATA_UPDATE`   | `DATA`         | FloatArray | Heart-rate samples (BPM)  |
 * | `com.urbandroid.sleep.watch.CONFIRM_CONNECTED`| `MAX_RAW_DATA` | Int        | **Required** batch size   |
 *
 * ### Actigraphy source
 * Raw accelerometer streaming (classic `0x0002` characteristic, enabled during
 * sleep tracking) feeds real movement magnitudes; when the stream is off, the
 * resting baseline (≈ 1 g) is reported so Sleep as Android always has a signal.
 */
@Singleton
class SleepAsAndroidSender @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
         * Updated at runtime from `SET_BATCH_SIZE` (`SIZE` extra).
         */
        private var actigraphyBatchSize = 20

        /** Minimum interval between HR batch flushes (10 seconds). */
        private const val HR_BATCH_INTERVAL_MS = 10_000L

        /** Actigraphy aggregation tick (spec: aggregate per 10-second intervals). */
        private const val MOVEMENT_TICK_MS = 10_000L

        /**
         * Stillness baseline: raw accelerometer magnitude at rest ≈ 1 g.
         *
         * Gadgetbridge sends `sqrt(x²+y²+z²)` raw (gravity included), which reads ~9.8
         * when still — a real sensor physically never reports 0.0 (that is free-fall).
         * Sending 0.0 marks the data as no-sensor/invalid downstream, so stillness
         * MUST be reported as ~9.8 for Sleep as Android to accept the stream.
         */
        private const val STILLNESS_BASELINE_MS2 = 9.8f
    }

    // Guarded by `this` — accessed from IO coroutines handling device events
    private val hrBuffer = mutableListOf<Float>()
    private val movementBuffer = mutableListOf<Float>()
    private var lastHrFlushMs = 0L

    // Raw-accelerometer aggregation window (Gadgetbridge pattern): the strongest
    // magnitude seen since the last tick. Falls back to stillness when the band
    // streams nothing (stream off / unsupported device).
    private var rawWindowMax = 0f
    private var rawWindowSeen = false

    private val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var movementTicker: Job? = null

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
     * Real accelerometer samples (Mi Band raw-sensor stream) only update the current
     * 10-second aggregation window — the window maximum is what gets batched, exactly
     * like Gadgetbridge aggregates `maxRawData`. This keeps high-rate sensor traffic
     * (tens of samples/sec) from spamming one broadcast per sample.
     *
     * @param magnitude Vector magnitude of the accelerometer reading (units of g).
     */
    fun onMovementData(magnitude: Float) {
        synchronized(this) {
            if (!rawWindowSeen || magnitude > rawWindowMax) {
                rawWindowMax = magnitude
                rawWindowSeen = true
            }
        }
    }

    /**
     * Start the stillness ticker: every 10 s (per spec aggregation interval) the
     * window maximum is batched — real accelerometer data when the band streams it,
     * [STILLNESS_BASELINE_MS2] otherwise ("no measurable movement").
     *
     * Why the fallback matters: without ANY `DATA_UPDATE`, Sleep as Android keeps
     * shrinking the requested batch and never engages its tracking loop (observed:
     * `batchSize 12 → 1 → 12 …` with an empty screen). The fallback value is the
     * physically-correct resting baseline (raw magnitude ≈ 1 g, as Gadgetbridge
     * sends when still) — never 0.0, which reads as no-sensor/invalid downstream.
     * HR — the other real signal here — then drives staging.
     */
    fun startMovementTicker() {
        synchronized(this) {
            if (movementTicker?.isActive == true) return
            // Bootstrap: one immediate sample so the stream opens without waiting
            // a full batch period.
            movementBuffer.add(STILLNESS_BASELINE_MS2)
            flushMovementBatch()
            movementTicker = senderScope.launch {
                while (true) {
                    delay(MOVEMENT_TICK_MS)
                    tickMovement()
                }
            }
        }
        Timber.i("SleepAsAndroidSender: movement ticker started")
    }

    /** Fold the current aggregation window into the batch. */
    private fun tickMovement() {
        synchronized(this) {
            val value = if (rawWindowSeen) rawWindowMax else STILLNESS_BASELINE_MS2
            rawWindowMax = 0f
            rawWindowSeen = false
            movementBuffer.add(value)
            if (movementBuffer.size >= actigraphyBatchSize) flushMovementBatch()
        }
    }

    fun stopMovementTicker() {
        synchronized(this) {
            movementTicker?.cancel()
            movementTicker = null
            if (movementBuffer.isNotEmpty()) flushMovementBatch()
        }
        Timber.i("SleepAsAndroidSender: movement ticker stopped")
    }

    /** Honor Sleep as Android's requested actigraphy batch size (`SET_BATCH_SIZE`). */
    fun setActigraphyBatchSize(size: Long) {
        synchronized(this) {
            actigraphyBatchSize = size.coerceIn(1, 120).toInt()
            // A mid-batch SIZE change would otherwise flush a wrong-sized batch, which
            // Sleep as Android flags ("unexpected batch size"). The buffered samples are
            // zeros, so dropping the partial batch loses no information.
            if (movementBuffer.isNotEmpty()) {
                Timber.d(
                    "SleepAsAndroidSender: dropping %d buffered sample(s) on batch-size change",
                    movementBuffer.size
                )
                movementBuffer.clear()
            }
        }
        Timber.d("SleepAsAndroidSender: actigraphy batch size → %d", actigraphyBatchSize)
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
            putExtra(EXTRA_MAX_RAW_DATA, actigraphyBatchSize)
        }
        context.sendBroadcast(intent)
        Timber.i(
            "SleepAsAndroidSender: sent CONFIRM_CONNECTED (batchSize=%d)",
            actigraphyBatchSize
        )
    }

    private fun broadcast(action: String) {
        context.sendBroadcast(Intent(action).setPackage(PACKAGE_SLEEP))
        Timber.i("SleepAsAndroidSender: → %s", action)
    }
}
