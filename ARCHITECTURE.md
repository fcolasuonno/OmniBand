# OmniBand — Architecture & Developer Guide

## Overview

OmniBand is a modern Android companion app supporting **Xiaomi Smart Band 7** and **Sony WF-1000XM5** earbuds, with full **Sleep as Android** integration. It is built with a clean, layered architecture using the latest Android best practices.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| Async | Coroutines + Flow |
| DI | Hilt |
| Database | Room |
| Preferences | DataStore (Preferences) |
| BLE | Android BluetoothGatt API |
| Lifecycle | LifecycleService, ViewModel, collectAsStateWithLifecycle |

---

## Layer Structure

```
com.omniband/
├── OmniBandApp.kt                 Application class (Hilt entry point)
│
├── ble/                           BLE layer
│   ├── BleManager.kt             Central connection manager (Singleton)
│   ├── ConnectionState.kt        Sealed hierarchy of connection states
│   ├── protocol/
│   │   ├── DeviceProtocol.kt     Abstract protocol interface + DeviceEvent types
│   │   ├── MiBand7Protocol.kt    Xiaomi Smart Band 7 implementation
│   │   └── SonyWF1000XM5Protocol.kt  Sony WF-1000XM5 implementation
│   └── reconnect/
│       └── ReconnectionManager.kt  Exponential-backoff reconnection
│
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt        Room database
│   │   ├── entity/Entities.kt    Room entities
│   │   └── dao/Daos.kt           Room DAOs
│   └── repository/
│       └── Repositories.kt       DeviceRepository, HealthRepository, UserPreferencesRepository
│
├── service/
│   ├── DeviceService.kt          Foreground service (LifecycleService)
│   ├── BootReceiver.kt           Reconnects after device reboot
│   ├── BluetoothStateReceiver.kt Reconnects after BT turns on
│   └── SleepAsAndroidReceiver.kt Sleep as Android bridge
│
├── di/
│   └── AppModule.kt              Hilt modules for DB, repositories
│
└── ui/
    ├── MainActivity.kt           Activity + NavHost
    ├── theme/                    Material 3 theme, typography, colors
    ├── screen/
    │   ├── DashboardScreen.kt    Live metrics, connection status
    │   ├── HealthScreen.kt       HR chart, steps, sleep, SpO2
    │   ├── DeviceScanScreen.kt   BLE scan + pair flow
    │   └── SettingsScreen.kt     Preferences + device management
    └── viewmodel/
        └── ViewModels.kt         DashboardVM, ScanVM, HealthVM, SettingsVM
```

---

## Connection Reliability

This is the most critical aspect of the app. Multiple layers defend against dropped connections:

### 1. GATT Error 133 Prevention
Android's most notorious BLE bug. Mitigated by:
- Calling `gatt.refresh()` via reflection before `gatt.close()` — clears stale GATT cache
- Waiting 600ms between `disconnect()` and `connectGatt()` — lets the BT stack settle
- Using `autoConnect=false` for initial connect (faster), then relying on the reconnection manager
- Requesting high MTU (512) after connect for better throughput

### 2. ReconnectionManager (Exponential Backoff)
```
delay = min(BASE × MULTIPLIER^attempt, MAX_CAP) × (1 ± 20% jitter)
     = min(1000ms × 1.8^n, 60000ms) × random_jitter
```
- Retries indefinitely (INT_MAX) — never gives up on your device
- Jitter prevents multiple devices from hammering the BT stack simultaneously
- Pauses automatically when Bluetooth adapter is off

### 3. Multiple Entry Points for Reconnection
- `DeviceService` auto-connects on app startup
- `BootReceiver` reconnects after Android reboot
- `BluetoothStateReceiver` reconnects when BT is toggled back on
- `ReconnectionManager` reconnects on any GATT disconnect/error

### 4. Foreground Service with START_STICKY
The `DeviceService` is a `LifecycleService` running in the foreground. It:
- Shows a persistent notification so Android doesn't kill it
- Returns `START_STICKY` — Android restarts it if killed under memory pressure
- Maintains a `SupervisorJob` scope so individual coroutine failures don't crash the service

---

## BLE Protocols

### Xiaomi Smart Band 7

Uses the **Huami 2021 / ZeppOS protocol** over BLE GATT:

**Authentication handshake (3-step):**
1. App → Band: `[0x01, 0x00, <16-byte auth key>]`
2. Band → App: `[0x02, 0x00, <16-byte random number>]`
3. App → Band: `[0x03, 0x00, AES-ECB-128(random_number, auth_key)]`
4. Band → App: `[0x03, 0x00, 0x01]` = success

**Auth key acquisition:** Use [xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor) with your Mi/Xiaomi account.

**Key UUIDs:**
```
Service (main):  0000fee0-0000-1000-8000-00805f9b34fb
Service (auth):  0000fee1-0000-1000-8000-00805f9b34fb
Auth char:       00000009-0000-3512-2118-0009af100700
Command char:    00000016-0000-3512-2118-0009af100700
Heart Rate char: 00000038-0000-3512-2118-0009af100700
Activity char:   00000007-0000-3512-2118-0009af100700
SpO2 char:       00000045-0000-3512-2118-0009af100700
Battery char:    00000006-0000-3512-2118-0009af100700
```

**Command framing:**
```
[type:1][seqHigh:1][seqLow:1][payload:N]
```

### Sony WF-1000XM5

Uses a **proprietary Sony serial protocol** over BLE GATT:

**Packet framing:**
```
[0x3E][dataType:1][seqId:1][lenHigh:1][lenLow:1][payload:N][checksum:1]
checksum = XOR(bytes[1..4+N])
```

**Key data types:**
| Type | Direction | Description |
|------|-----------|-------------|
| 0x00 | → device  | Init handshake |
| 0x01 | ← device  | Init response |
| 0x0C | ← device  | Battery report (L/R/Case) |
| 0x10 | → device  | Request battery |
| 0x17 | → device  | Request ANC mode |
| 0x68 | ↔ both    | ANC mode report/set |
| 0x56 | ← device  | Wearing state |
| 0x8A | ← device  | Noise level |

**ANC mode values:** `0x00=Off`, `0x01=NC`, `0x02=Wind`, `0x11=Ambient`

---

## Sleep as Android Integration

The integration uses the documented [Sleep as Android BLE device API](https://docs.sleep.urbandroid.org/devs/ble-device-api.html):

**Incoming broadcasts (Sleep as Android → OmniBand):**
| Action | Effect on device |
|--------|-----------------|
| `SLEEP_TRACKING_STARTED` | Enable continuous HR + SpO2 on Mi Band 7; switch Sony to NC mode |
| `SLEEP_TRACKING_STOPPED` | Disable continuous monitoring |
| `ALARM_ALERT_START` | Trigger alarm vibration on band |
| `ALARM_ALERT_DISMISS` | Stop alarm vibration |
| `ALARM_SNOOZE_CLICKED` | Stop alarm briefly |

**Outgoing actigraphy data (OmniBand → Sleep as Android):**
```kotlin
Intent("com.urbandroid.sleep.watch.INTENT_WATCH_DATA").apply {
    setPackage("com.urbandroid.sleep")
    putExtra("com.urbandroid.sleep.watch.DATA", floatArrayOf(/* 1Hz movement magnitudes */))
}
```
The `SleepAsAndroidBridge` buffers accelerometer `|xyz|` magnitudes at 1Hz and flushes them in 20-sample batches to Sleep as Android for improved sleep stage detection.

---

## Data Flow

```
Device (BLE)
    │  GATT notifications
    ▼
BleManager.gattCallback
    │  onCharacteristicChanged
    ▼
DeviceProtocol (MiBand7Protocol / SonyWF1000XM5Protocol)
    │  parsed DeviceEvent (HeartRate, Steps, Battery, SpO2, …)
    ▼
BleManager.deviceEvents  ← SharedFlow
    │
    ├──▶ DeviceService.observeDeviceEvents()
    │       └──▶ HealthRepository.save*(...)
    │                   └──▶ Room DB
    │
    └──▶ DashboardViewModel.observeDeviceEvents()
                └──▶ StateFlow → Compose UI (collectAsStateWithLifecycle)
```

---

## Adding a New Device

1. Create `MyDeviceProtocol : DeviceProtocol` in `ble/protocol/`
2. Add entry to `DeviceType` enum with `namePattern` matching the BLE advertisement name
3. Register in `BleManager.createProtocol()`
4. If it needs auth: handle in `BleManager.connect()` and `SettingsScreen`

---

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (needs keystore)
./gradlew assembleRelease

# Run tests
./gradlew test
```

**Minimum requirements:**
- Android Studio Ladybug (2024.2+)
- JDK 17
- Android device with BLE (API 26+)

---

## Known Limitations & Future Work

- **Mi Band 7 auth key**: Must be extracted externally (Xiaomi doesn't expose it via API)
- **Sony WF-1000XM5 BT Classic**: A2DP/HFP is handled by Android's system stack; we only control the BLE channel
- **GATT error 133**: Fully mitigated but inherent to Android's BT stack on some devices/ROM combos
- **Future**: Add notification mirroring, firmware update support, widget
