# OmniBand — Architecture & Developer Guide

## Overview

OmniBand is a modern Android companion app supporting **Xiaomi Smart Band 7** and
**Sony WF-1000XM5** earbuds, with full **Sleep as Android** integration. It is built with
a clean, layered architecture using the latest Android best practices.

---

## Tech Stack

| Layer       | Technology                                               |
|-------------|----------------------------------------------------------|
| Language    | Kotlin 2.0                                               |
| UI          | Jetpack Compose + Material 3                             |
| Async       | Coroutines + Flow                                        |
| DI          | Hilt                                                     |
| Database    | Room                                                     |
| Preferences | DataStore (Preferences)                                  |
| BLE         | Android BluetoothGatt API                                |
| Lifecycle   | LifecycleService, ViewModel, collectAsStateWithLifecycle |

---

## Layer Structure

```
nodomain.freeyourgadget.gadgetbridge/
├── OmniBandApp.kt                    Application class (Hilt entry point)
│
├── ble/
│   ├── BleManager.kt                 Central connection manager (Singleton)
│   ├── ConnectionState.kt            Sealed hierarchy of connection states
│   ├── protocol/
│   │   ├── DeviceProtocol.kt         Abstract protocol interface + DeviceEvent types
│   │   ├── Huami2021Chunked.kt       ZeppOS chunked-transfer framing + crypto
│   │   ├── ECDH_B163.kt              NIST B-163 elliptic-curve helpers
│   │   ├── MiBand7Protocol.kt        Xiaomi Smart Band 7 (ZeppOS) implementation
│   │   └── SonyWF1000XM5Protocol.kt  Sony WF-1000XM5 implementation
│   └── reconnect/
│       └── ReconnectionManager.kt    Exponential-backoff reconnection
│
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt            Room database
│   │   ├── entity/Entities.kt        Room entities
│   │   └── dao/Daos.kt               Room DAOs
│   └── repository/
│       └── Repositories.kt           DeviceRepository, HealthRepository, UserPreferencesRepository
│
├── service/
│   ├── DeviceService.kt              Foreground service (LifecycleService)
│   ├── SleepAsAndroidReceiver.kt     Receives Sleep as Android broadcasts → forwards to band
│   └── SleepAsAndroidSender.kt       Sends HR + actigraphy data to Sleep as Android
│
├── di/
│   └── AppModule.kt                  Hilt modules (DB, repositories)
│
└── ui/
    ├── MainActivity.kt               Activity + NavHost
    ├── theme/                         Material 3 theme, typography, colours
    ├── screen/
    │   ├── DashboardScreen.kt         Live metrics + connection status
    │   ├── HealthScreen.kt            HR chart, steps, sleep, SpO₂
    │   ├── DeviceScanScreen.kt        BLE scan + pair flow
    │   └── SettingsScreen.kt          Preferences + device management
    └── viewmodel/
        └── ViewModels.kt              DashboardVM, ScanVM, HealthVM, SettingsVM
```

---

## Connection Reliability

Multiple layers defend against dropped connections:

### 1 — GATT Error 133 Prevention

Android's most notorious BLE bug. Mitigated by:
- Calling `gatt.refresh()` via reflection before `gatt.close()` — clears stale GATT cache
- Waiting 600 ms between `disconnect()` and `connectGatt()` — lets the BT stack settle
- Using `autoConnect=false` for the initial connect (faster), then relying on
  `ReconnectionManager` for subsequent attempts
- Requesting a high MTU (512) after connect for better throughput

### 2 — ReconnectionManager (Exponential Backoff)
```
delay = min(BASE × MULTIPLIER^attempt, MAX_CAP) × (1 ± 20% jitter)
      = min(1000ms × 1.8^n, 60000ms) × random_jitter
```

- Retries indefinitely (`Int.MAX_VALUE`) — never gives up on your device
- Jitter prevents multiple devices from thundering against the BT stack simultaneously
- Pauses automatically when the Bluetooth adapter is switched off

### 3 — Multiple Reconnection Entry Points

| Trigger                     | Handler                  |
|-----------------------------|--------------------------|
| App launch / manual connect | `DeviceService.onCreate` |
| Android reboot              | `BootReceiver`           |
| Bluetooth toggled back on   | `BluetoothStateReceiver` |
| GATT disconnect / error     | `ReconnectionManager`    |

### 4 — Foreground Service with START_STICKY

`DeviceService` is a `LifecycleService` running in the foreground. It:

- Shows a persistent notification so Android does not kill it
- Returns `START_STICKY` — Android restarts it if killed under memory pressure
- Maintains a `SupervisorJob` scope so individual coroutine failures don't crash the service

---

## BLE Protocols

### Xiaomi Smart Band 7 (ZeppOS)

Uses the **Huami 2021 Extended Header** (a.k.a. ZeppOS chunked-transfer) protocol.

**Key characteristics:**

| Role             | UUID (suffix)      | Direction    |
|------------------|--------------------|--------------|
| Chunked write    | `0x0016` (0009af…) | Phone → Band |
| Chunked read     | `0x0017` (0009af…) | Band → Phone |
| Standard HR      | `0x2A37` (GATT)    | Band → Phone |
| Standard Battery | `0x2A19` (GATT)    | Band → Phone |

#### Authentication (ECDH B-163 + AES-128)

> **Note:** The Mi Band 7 uses ZeppOS firmware and requires the full ECDH handshake
> described below. This is **not** the older AES-only auth used by Mi Band 4/5/6.

1. **Phone → Band** *(endpoint 0x0082)*: phone's B-163 EC public key
   ```
   [CMD=0x04][0x02][0x00][0x02][pubKey: 48 bytes]
   ```

2. **Band → Phone** *(endpoint 0x0082)*: band's public key + random nonce
   ```
   [0x10][CMD=0x04][STATUS=0x01][nonce: 16 bytes][bandPubKey: 48 bytes]
   ```

3. Phone computes ECDH shared secret, then derives the session key:
   ```
   sharedSecret = ECDH_B163.generateShared(phonePrivKey, bandPubKey)
   sessionKey[i] = sharedSecret[i + 8] XOR authKey[i]     (i = 0..15)
   initialSeq    = LE32(sharedSecret[0..3])
   ```

4. **Phone → Band** *(endpoint 0x0082)*: double-encrypted nonces
   ```
   [CMD=0x05][AES-ECB(authKey, nonce): 16][AES-ECB(sessionKey, nonce): 16]
   ```

5. **Band → Phone** *(endpoint 0x0082)*: authentication result
   ```
   [0x10][CMD=0x05][STATUS=0x01]   ← success
   ```

After step 5, all communication on *encrypted endpoints* (battery, configuration,
find-my-device, …) uses AES-128/ECB with a per-message key:

```
messageKey[i] = sessionKey[i] XOR handle     (i = 0..15)
```

**Auth key acquisition:** Use
[xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)
with your Xiaomi account to retrieve the 16-byte hex auth key.

#### Chunked-transfer framing (`Huami2021Chunked`)

```
First packet  (11-byte header):
  [0x03][flags][0x00][handle][count][origLen: 4 LE][endpoint: 2 LE][data...]

Continuation  (5-byte header):
  [0x03][flags][0x00][handle][count][data...]

flags: bit0=First  bit1=Last  bit2=NeedsAck  bit3=Encrypted

ACK (phone→band, written to chunkedWrite / 0x0016):
  [0x04][0x00][handle][0x01][count]
```

#### Key endpoints (partial list)

| Endpoint | Description                 | Encrypted |
|----------|-----------------------------|-----------|
| `0x0001` | Service list                | No        |
| `0x0082` | Auth (ECDH key + responses) | No        |
| `0x001d` | Heart rate                  | No        |
| `0x0016` | Steps / activity            | No        |
| `0x0029` | Battery                     | **Yes**   |
| `0x002d` | Configuration               | **Yes**   |
| `0x001a` | Find device                 | **Yes**   |

---

### Sony WF-1000XM5

Uses a **proprietary Sony serial protocol** over BLE GATT.

**Packet framing:**
```
[0x3E][dataType: 1][seqId: 1][lenHigh: 1][lenLow: 1][payload: N][checksum: 1]
checksum = XOR of bytes[1..4+N]
```

**Key data types:**

| Type | Direction | Description               |
|------|-----------|---------------------------|
| 0x00 | → device  | Init handshake            |
| 0x01 | ← device  | Init response             |
| 0x0C | ← device  | Battery report (L/R/Case) |
| 0x10 | → device  | Request battery           |
| 0x17 | → device  | Request ANC mode          |
| 0x68 | ↔ both    | ANC mode report / set     |
| 0x56 | ← device  | Wearing state             |
| 0x8A | ← device  | Noise level               |

**ANC mode values:** `0x00=Off`  `0x01=NC`  `0x02=Wind`  `0x11=Ambient`

---

## Sleep as Android Integration

Full spec: https://docs.sleep.urbandroid.org/devs/ble-device-api.html

### Incoming broadcasts (Sleep as Android → OmniBand)

| Action                               | Effect on device                  |
|--------------------------------------|-----------------------------------|
| `…watch.START_TRACKING`              | Enable continuous HR + SpO₂       |
| `…watch.STOP_TRACKING`               | Disable continuous monitoring     |
| `…watch.START_ALARM`                 | Trigger alarm vibration on band   |
| `…watch.STOP_ALARM`                  | Stop alarm vibration              |
| `…watch.CHECK_CONNECTED`             | Reply with `CONFIRM_CONNECTED`    |
| `…watch.HINT`                        | Short vibration hint              |
| `…alarmclock.SLEEP_TRACKING_STARTED` | Same as `START_TRACKING` (legacy) |
| `…alarmclock.ALARM_ALERT_START`      | Same as `START_ALARM` (legacy)    |

### Outgoing broadcasts (OmniBand → Sleep as Android)

#### Connection confirmation — **must** include `MAX_RAW_DATA`

```kotlin
Intent("com.urbandroid.sleep.watch.CONFIRM_CONNECTED").apply {
    setPackage("com.urbandroid.sleep")
    putExtra("MAX_RAW_DATA", 20)   // ← required; declares actigraphy batch size
}
```

> Without the `MAX_RAW_DATA` integer extra, Sleep as Android accepts the connection
> but never requests tracking data — the integration appears connected but is silent.

#### Heart-rate data

```kotlin
Intent("com.urbandroid.sleep.watch.HR_DATA_UPDATE").apply {
    setPackage("com.urbandroid.sleep")
    putExtra("DATA", floatArrayOf(72f, 71f, 73f, …))   // FloatArray of BPM samples
}
```

#### Actigraphy data *(not available on Mi Band 7)*
```kotlin
Intent("com.urbandroid.sleep.watch.DATA_UPDATE").apply {
    setPackage("com.urbandroid.sleep")
    putExtra(
        "MAX_RAW_DATA",
        floatArrayOf(0.12f, 0.08f, …
    ))  // FloatArray of |xyz| magnitudes at 1 Hz
}
```

> The Mi Band 7 ZeppOS protocol does not expose raw accelerometer data. Sleep as Android
> will use HR-only sleep staging.  `DATA_UPDATE` is implemented in `SleepAsAndroidSender`
> for future use should accelerometer access become available.

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
BleManager.deviceEvents  ←── SharedFlow (replay=1)
    │
    ├──▶ DeviceService.observeDeviceEvents()
    │       ├──▶ HealthRepository.save*(…) ──▶ Room DB
    │       └──▶ SleepAsAndroidSender.on*(…) ──▶ Sleep as Android broadcasts
    │
    └──▶ DashboardViewModel
                └──▶ StateFlow ──▶ Compose UI (collectAsStateWithLifecycle)
```

---

## Adding a New Device

1. Create `MyDeviceProtocol : DeviceProtocol` in `ble/protocol/`.
2. Add an entry to the `DeviceType` enum with a `namePattern` matching the BLE advertisement name.
3. Register the new type in `BleManager.createProtocol()`.
4. If it needs an auth key, add a corresponding entry in `SettingsScreen` and
   `UserPreferencesRepository`.

---

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (needs a signing keystore)
./gradlew assembleRelease

# Unit tests
./gradlew test
```

**Minimum requirements:**
- Android Studio Ladybug (2024.2+)
- JDK 17
- Android device with BLE (API 26+)

---

## Known Limitations & Future Work

- **Mi Band 7 auth key**: Must be extracted externally via
  [xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor).
- **Mi Band 7 accelerometer**: ZeppOS does not expose raw sensor data over the chunked BLE
  protocol; Sleep as Android therefore operates in HR-only sleep-staging mode.
- **Sony WF-1000XM5 A2DP/HFP**: Handled by Android's system BT stack; OmniBand only
  controls the BLE configuration channel.
- **GATT error 133**: Fully mitigated at the application level but ultimately inherent to
  Android's BT stack on certain device/ROM combinations.
- **Future**: Notification mirroring, firmware update support, home-screen widget.
