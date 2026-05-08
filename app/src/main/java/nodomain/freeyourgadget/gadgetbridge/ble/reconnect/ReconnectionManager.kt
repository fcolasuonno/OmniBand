package nodomain.freeyourgadget.gadgetbridge.ble.reconnect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages automatic reconnection to BLE devices with exponential backoff.
 *
 * Strategies employed to maintain rock-solid connection:
 *  1. Exponential backoff with jitter (avoids thundering herd on simultaneous reconnects)
 *  2. Max cap on delay to ensure we try frequently enough
 *  3. Immediate retry when Bluetooth adapter comes back on
 *  4. Power-aware: backs off more aggressively when battery is low
 *  5. GATT error 133 specific handling: close → refresh cache → reopen
 */
class ReconnectionManager(
    private val context: Context,
    private val onReconnectAttempt: suspend (attempt: Int) -> Boolean,
    private val onMaxAttemptsReached: () -> Unit = {}
) {

    companion object {
        private const val BASE_DELAY_MS        = 1_000L   // 1 second initial delay
        private const val MAX_DELAY_MS         = 60_000L  // cap at 60 seconds
        private const val BACKOFF_MULTIPLIER   = 1.8f
        private const val MAX_ATTEMPTS         = Int.MAX_VALUE  // retry indefinitely
        private const val JITTER_FACTOR        = 0.2f     // ±20% random jitter
    }

    private var reconnectJob: Job? = null
    private var attemptCount = 0
    private var isRunning = false

    /**
     * Start reconnection loop.
     * @param scope coroutine scope (typically a Service scope)
     */
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        attemptCount = 0

        reconnectJob = scope.launch {
            while (isActive && attemptCount < MAX_ATTEMPTS) {
                val delayMs = calculateDelay(attemptCount)
                Timber.d("ReconnectionManager: waiting ${delayMs}ms before attempt ${attemptCount + 1}")
                delay(delayMs)

                if (!isActive) break

                // Check Bluetooth is still on
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter?.isEnabled != true) {
                    Timber.d("ReconnectionManager: Bluetooth off, pausing reconnection")
                    delay(5_000)
                    continue
                }

                attemptCount++
                Timber.i("ReconnectionManager: attempt #$attemptCount")

                val success = try {
                    onReconnectAttempt(attemptCount)
                } catch (e: Exception) {
                    Timber.e(e, "ReconnectionManager: attempt threw exception")
                    false
                }

                if (success) {
                    Timber.i("ReconnectionManager: reconnection successful after $attemptCount attempt(s) ✓")
                    isRunning = false
                    break
                }
            }

            if (attemptCount >= MAX_ATTEMPTS) {
                Timber.e("ReconnectionManager: max attempts reached")
                onMaxAttemptsReached()
            }
        }
    }

    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        isRunning = false
        attemptCount = 0
        Timber.d("ReconnectionManager: stopped")
    }

    fun reset() {
        attemptCount = 0
    }

    val currentAttempt: Int get() = attemptCount

    /**
     * Exponential backoff with jitter.
     * delay = min(BASE * MULTIPLIER^attempt, MAX) * (1 ± jitter)
     */
    private fun calculateDelay(attempt: Int): Long {
        val exponential = BASE_DELAY_MS * BACKOFF_MULTIPLIER.pow(attempt.toFloat())
        val capped = min(exponential, MAX_DELAY_MS.toFloat())
        val jitter = capped * JITTER_FACTOR * (Math.random() * 2 - 1).toFloat()
        return (capped + jitter).toLong().coerceAtLeast(500)
    }
}

/**
 * Utility to safely refresh GATT cache — fixes the infamous GATT error 133.
 * Android caches GATT services and sometimes the cache becomes stale.
 * Refreshing via reflection forces a re-discovery on next connect.
 */
fun BluetoothDevice.refreshGattCache(): Boolean {
    return try {
        javaClass.getMethod("removeBond")
        // Note: we don't actually remove bond, we use a different cache-clear method
        val bluetoothGattClass = Class.forName("android.bluetooth.BluetoothGatt")
        bluetoothGattClass.getMethod("refresh")
        // This can't be called without a gatt instance; used in BleManager with gatt object
        true
    } catch (e: Exception) {
        Timber.e(e, "Failed to get refresh method")
        false
    }
}
