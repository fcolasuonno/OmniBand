package com.omniband.ble

// ---------------------------------------------------------------------------
// Connection state sealed hierarchy
// ---------------------------------------------------------------------------

sealed class ConnectionState {
    /** No device paired / selected */
    data object Disconnected : ConnectionState()

    /** Actively scanning for the target device */
    data object Scanning : ConnectionState()

    /** Found device, opening GATT */
    data class Connecting(val address: String, val deviceType: DeviceType) : ConnectionState()

    /** GATT connected, running service discovery + auth handshake */
    data class Initializing(val address: String, val deviceType: DeviceType) : ConnectionState()

    /** Fully ready, can exchange data */
    data class Connected(val address: String, val deviceType: DeviceType) : ConnectionState()

    /** Lost connection, reconnection manager is active */
    data class Reconnecting(val address: String, val deviceType: DeviceType, val attempt: Int) : ConnectionState()

    /** Permanent failure (e.g., auth rejected) */
    data class Error(val message: String) : ConnectionState()
}

val ConnectionState.isConnected: Boolean
    get() = this is ConnectionState.Connected

val ConnectionState.address: String?
    get() = when (this) {
        is ConnectionState.Connecting -> address
        is ConnectionState.Initializing -> address
        is ConnectionState.Connected -> address
        is ConnectionState.Reconnecting -> address
        else -> null
    }

// ---------------------------------------------------------------------------
// Device types
// ---------------------------------------------------------------------------

enum class DeviceType(
    val displayName: String,
    val namePattern: String,
    val serviceUuid: String?,
) {
    XIAOMI_SMART_BAND_7(
        displayName = "Xiaomi Smart Band 7",
        namePattern = "Xiaomi Smart Band 7",
        serviceUuid = "0000fee0-0000-1000-8000-00805f9b34fb",
    ),
    SONY_WF1000XM5(
        displayName = "Sony WF-1000XM5",
        namePattern = "WF-1000XM5",
        serviceUuid = "75c27625-bd42-d645-0b00-a4acd5dfb3b4",
    );

    companion object {
        fun fromDeviceName(name: String?): DeviceType? =
            name?.let { n -> entries.firstOrNull { n.contains(it.namePattern, ignoreCase = true) } }
    }
}

// ---------------------------------------------------------------------------
// Scan result
// ---------------------------------------------------------------------------

data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val deviceType: DeviceType?
)
