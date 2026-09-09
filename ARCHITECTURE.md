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

> **Note:** Modern ZeppOS firmware (Band 7, and Band 6 on current firmware)
> requires the full ECDH handshake below. Older Band 4/5/6 firmware uses the
> AES-only challenge-response auth — see [Xiaomi Smart Band 6](#xiaomi-smart-band-6).

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

ACK (phone→band, written to chunkedRead / 0x0017):
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
| `0x000a` | Configuration               | **Yes**   |
| `0x001a` | Find device                 | **Yes**   |

---

### Xiaomi Smart Band 6

The Band 6 sits at a protocol transition:

- **Older firmware** uses the classic Huami flow: server-based **Pairing v2** once,
  then an AES challenge-response **Authentication** on every connect.
- **Modern firmware** uses the **same ECDH chunked-transfer flow as the Band 7**
  above — verified live: our test unit authenticates via ECDH on endpoint `0x0082`
  and then speaks the ZeppOS endpoint set (battery `0x0029`, steps `0x0016`,
  HR `0x001d`, config `0x000a`, …).

Our `MiBand7Protocol` (Huami 2021 ECDH) therefore drives modern-firmware Band 6
units. The classic flow below is documented as the fallback
(Gadgetbridge's `InitOperation2021` tries ECDH first, then challenge-response).

Reference: **BreakMi** — Casagrande et al., *"Reversing, Exploiting and Fixing
Xiaomi Fitness Tracking Ecosystem"*, TCHES 2022
([paper](https://tches.iacr.org/index.php/TCHES/article/download/9704/9234),
[PDF mirror](https://nebelwelt.net/files/22CHES.pdf)).

#### Classic Pairing v2 (server-based, Band 4/5/6)

```
App → Band : Pairing Init
Band → App : pair_v2 + SHA1(pub_k)          (truncated digest of band's BT pubkey)
App → Band : Random Req (820002)
Band → App : R (16-byte seed, in the clear)
App        : Key = SHA256(TR_A || R)[0:16]  (TR_A = band's public BT address)
App ↔ Cloud: send SHA1(pub_k) + base64(Key), receive base64(Sig)
App → Band : Sig → band verifies → Valid Sig
…then user confirms on band + app (same as Pairing v1)
```

#### Classic Authentication (every connect)

```
App → Band : Auth Req (0200 or 820002)      on Auth char 00000009-… under 0xFEE1
Band → App : Chal (16 bytes)
App → Band : Resp = AES-ECB(Key, Chal)
Band → App : Auth OK (100301 / 108301) or Auth FAIL
```

Bands 4/5/6 also accept the older Band 2/3 opcodes. Full opcode table (BreakMi Table 2):

| Message           | Sender  | Opcode / Value                     |
|-------------------|---------|------------------------------------|
| Pairing Init      | App     | `0100`                             |
| pair_v1           | Tracker | `100104`                           |
| Pairing Key       | App     | `0100` + Key                       |
| Pairing Complete  | Tracker | `100101`                           |
| Pairing Fail      | Tracker | `100204`                           |
| pair_v2           | Tracker | `10018101`                         |
| SHA1(pub_k)       | Tracker | `1863c2cce5d159413bed92c4b163c279` |
| Random Req        | App     | `820002`                           |
| Random Resp       | Tracker | `108201` + R                       |
| User Confirmation | Tracker | `108301`                           |
| Server Check      | Tracker | `10008401010000`                   |
| Auth Req          | App     | `0200` or `820002`                 |
| Auth Chal         | Tracker | `100201`+Chal or `108201`+Chal     |
| Auth Resp         | App     | `0300`+Resp or `8300`+Resp         |
| Auth Complete     | Tracker | `100301` or `108301`               |
| Auth Fail         | Tracker | `100304` or `108307`               |

Pairing v1/v2 messages use the Auth characteristic
(`00000009-0000-3512-2118-0009af100700`) under service `0xFEE1`; the v2 signature
goes over the Chunked Transfer characteristic
(`00000020-0000-3512-2118-0009af100700`) under `0xFEE1`.

#### GATT inventory relevant to Band 6 (BreakMi Tables 6 & 7)

Standard services:

| Service                     | Characteristic                    | Props   | Notes                  |
|-----------------------------|-----------------------------------|---------|------------------------|
| Heart Rate `0x180D`         | Measurement `0x2A37`              | N       | all bands              |
| Heart Rate `0x180D`         | Control Point `0x2A39`            | R, W    | all bands              |
| Battery `0x180F`            | Level `0x2A19`                    | N, R    | (also ZeppOS fallback) |
| Alert Notification `0x1811` | Control Point `0x2A44`            | N, R, W | all bands              |
| Immediate Alert `0x1802`    | Alert Level `0x2A06`              | WWR     | all bands              |
| Device Info `0x180A`        | Hw/Sw Revision, System ID, PnP ID | R       | all bands              |

Huami vendor service `0000fee0-…` (selected characteristics):

| Characteristic                                     | Props      | Bands                  |
|----------------------------------------------------|------------|------------------------|
| Current Time `0x2A2B`                              | N, R, W    | all                    |
| Chunked Transfer `…00000020…`                      | N, R, WWR  | all                    |
| Config `…00000003…`                                | N, WWR     | all                    |
| Activity Data `…00000005…`                         | N          | all                    |
| Battery `…00000006…`                               | N, R       | all                    |
| Steps `…00000007…`                                 | N, R       | all                    |
| User Settings `…00000008…`                         | N, W       | all                    |
| Auth `…00000009…` (under `0xFEE1`)                 | N, R, WWR  | all                    |
| Device Event `…00000010…`                          | N          | all                    |
| `…0000000e…` / `…0000000f…`                        | W / N, WWR | 3, 5, 6                |
| `…00000011…` / `…00000012…` / `…00000013…` (Audio) | N, R, WWR  | 4, 5, 6                |
| `…00000016…` (chunked write)                       | N, WWR     | 5 (→ ZeppOS write)     |
| `…00000017…` (chunked read)                        | N, WWR     | 5, 6 (→ ZeppOS notify) |
| `…0000fec1…`                                       | N, R, W    | 2, 3, 5, 6             |

Note the `…16`/`…17` rows: on Band 5/6-era firmware these are the same chunked
pair our ZeppOS implementation uses (`0x0016` write, `0x0017` notify) —
confirming the Band 6 speaks the Huami-2021 framing once past auth.

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

Full spec: https://sleep.urbandroid.org/docs/devs/wearable_api.html

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

#### Actigraphy data
```kotlin
Intent("com.urbandroid.sleep.watch.DATA_UPDATE").apply {
    setPackage("com.urbandroid.sleep")
    putExtra(
        "MAX_RAW_DATA",
        floatArrayOf(9.81f, 9.83f, …
    ))  // FloatArray of |xyz| magnitudes (g units), 10 s aggregation windows
}
```

> Raw accelerometer streaming comes from the classic `0x0002` characteristic
> (started/stopped with sleep tracking; re-enabled every 10 s). Samples are
> max-aggregated per 10 s window Gadgetbridge-style; when the stream is off, the
> resting baseline (≈ 1 g) is reported so the channel never goes silent.

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
- **Mi Band 7 accelerometer**: raw sensor streams over the classic `0x0002`
  characteristic (not the chunked protocol) during sleep tracking; the resting
  baseline is reported when the stream is off.
- **Sony WF-1000XM5 A2DP/HFP**: Handled by Android's system BT stack; OmniBand only
  controls the BLE configuration channel.
- **GATT error 133**: Fully mitigated at the application level but ultimately inherent to
  Android's BT stack on certain device/ROM combinations.
- **Future**: Notification mirroring, firmware update support, home-screen widget.
