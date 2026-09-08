package nodomain.freeyourgadget.gadgetbridge.ble.reconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.ble.reconnect.ReconnectionManager.Companion.JITTER_FACTOR
import nodomain.freeyourgadget.gadgetbridge.ble.reconnect.ReconnectionManager.Companion.MAX_ATTEMPTS
import timber.log.Timber
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages automatic BLE reconnection with exponential backoff + jitter.
 *
 * ## Strategy
 *
 * | Layer | Mechanism |
 * |-------|-----------|
 * | Backoff | `delay = min(BASE × MULTIPLIERⁿ, MAX_CAP) × (1 ± 20% jitter)` |
 * | BT check | Skip attempt if adapter reports disabled; retry after 5 s |
 * | Persistence | Retries indefinitely (`Int.MAX_VALUE`) — never gives up |
 * | Clean stop | [stop] cancels the job and resets the attempt counter |
 *
 * The caller is responsible for detecting a successful connection and calling [stop].
 *
 * @param isBluetoothEnabled  Checked before every attempt; avoids pointless connects while BT is off.
 * @param onReconnectAttempt  Suspending callback invoked for each attempt. Return `true` if the
 *                            connect call succeeded (not necessarily authenticated yet).
 * @param onMaxAttemptsReached  Called if [MAX_ATTEMPTS] is ever reached (currently never, since
 *                              it is `Int.MAX_VALUE`).
 */
class ReconnectionManager(
    private val isBluetoothEnabled: () -> Boolean,
    private val onReconnectAttempt: suspend (attempt: Int) -> Boolean,
    private val onMaxAttemptsReached: () -> Unit = {},
) {
    companion object {
        private const val BASE_DELAY_MS = 1_000L    // 1 s initial delay
        private const val MAX_DELAY_MS = 60_000L   // cap at 60 s
        private const val BACKOFF_MULTIPLIER = 1.8f
        private const val MAX_ATTEMPTS = Int.MAX_VALUE
        private const val JITTER_FACTOR = 0.2f      // ±20 % random jitter
    }

    private var reconnectJob: Job? = null
    private var attemptCount = 0

    val currentAttempt: Int get() = attemptCount

    /**
     * Start the reconnection loop inside [scope].
     * Silently returns if the loop is already running.
     */
    fun start(scope: CoroutineScope) {
        if (reconnectJob?.isActive == true) return
        attemptCount = 0

        reconnectJob = scope.launch {
            while (isActive && attemptCount < MAX_ATTEMPTS) {
                val delayMs = calculateDelay(attemptCount)
                Timber.d(
                    "ReconnectionManager: waiting %d ms before attempt %d",
                    delayMs,
                    attemptCount + 1
                )
                delay(delayMs)

                if (!isActive) break

                if (!isBluetoothEnabled()) {
                    Timber.d("ReconnectionManager: Bluetooth off — pausing for 5 s")
                    delay(5_000)
                    continue
                }

                attemptCount++
                Timber.i("ReconnectionManager: attempt #%d", attemptCount)

                val success = try {
                    onReconnectAttempt(attemptCount)
                } catch (e: Exception) {
                    Timber.e(e, "ReconnectionManager: attempt threw exception")
                    false
                }

                if (success) {
                    Timber.i(
                        "ReconnectionManager: reconnection handed off after %d attempt(s) ✓",
                        attemptCount
                    )
                    break
                }
            }

            if (attemptCount >= MAX_ATTEMPTS) {
                Timber.e("ReconnectionManager: max attempts reached")
                onMaxAttemptsReached()
            }
        }
    }

    /** Cancel the reconnection loop and reset the attempt counter. */
    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        attemptCount = 0
        Timber.d("ReconnectionManager: stopped")
    }

    /** Reset the attempt counter without stopping the loop (e.g., after a partial success). */
    fun reset() {
        attemptCount = 0
    }

    /**
     * Exponential backoff with ±[JITTER_FACTOR] random jitter.
     * ```
     * delay = clamp(BASE × MULTIPLIER^attempt, 500 ms, MAX_CAP) × (1 ± jitter)
     * ```
     */
    private fun calculateDelay(attempt: Int): Long {
        val exponential = BASE_DELAY_MS * BACKOFF_MULTIPLIER.pow(attempt.toFloat())
        val capped = min(exponential, MAX_DELAY_MS.toFloat())
        val jitter = capped * JITTER_FACTOR * (Math.random() * 2 - 1).toFloat()
        return (capped + jitter).toLong().coerceAtLeast(500)
    }
}
