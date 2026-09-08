# AGENTS.md — P2P File Broadcaster

## Project Overview

Android-only P2P file-sharing app. Kotlin, Jetpack Compose, BLE GATT for file transfer, RSA/AES-GCM PKI.

## Build & Test Commands

```bash
# Build debug APK (requires Android SDK)
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run tests on connected device
./gradlew connectedDebugAndroidTest
```

## Architecture

- **Single-activity** Compose app with bottom nav (Broadcasts | Subscriptions | Log)
- **BLE GATT** is the primary transfer mechanism (not Wi-Fi Direct — dropped)
- **16-bit service UUID**: `00006d38-0000-1000-8000-00805f9b34fb` — fits in 31B legacy advertising packet (21B on wire). Derived from `6d388575-46d6-4e84-9384-b14fb2006b20` via uuidgen.
- **Notification-based streaming**: Peripheral pushes chunks via `notifyCharacteristicChanged` (512B per chunk, 10ms sleep between chunks)
- **Samsung BLE quirk**: The BLE stack silently drops service data from scan results. Fixed with periodic scan restart (every 5 minutes).
- **Encrypted payloads**: Files are compressed first, then encrypted with a hybrid RSA/AES-GCM envelope before transfer.

## Key Constraints

- **compileSdk 36, minSdk 26, targetSdk 36**
- **Java 17** required (jvmToolchain)
- Unit tests use Robolectric + MockK
- `testOptions { unitTests.isReturnDefaultValues = true }` — Android framework methods return defaults in tests
- BLE operations require `BLUETOOTH_SCAN` (with `neverForLocation`), `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` permissions (Android 12+); `ACCESS_COARSE_LOCATION` (`maxSdkVersion=30`) only on Android 8–11 where the OS requires it for BLE scans — never used for position
- No `INTERNET` permission: not declared by the app and stripped from the merged manifest via `tools:node="remove"` (libraries re-add it). Do not re-add without a genuine network feature.
- Foreground service (`connectedDevice`) with partial wake lock required to keep BLE alive with screen off — always on, no user toggle (Play FGS policy)
- **Battery optimization**: App checks at startup and opens the system battery-optimization settings list (guidance only). Never fire `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — restricted by Play policy; the permission is intentionally undeclared
- **UGC moderation**: users accept community terms (EULA §3) before first broadcast/subscribe; subscriptions have Report/Block actions; `SettingsStore.blockedFileIds` are never fetched, pushed, or relayed (enforced in `SyncEngine`); reports go to the GitHub issue tracker

## Transfer Limits

- **Max file size**: Unlimited (no enforced limit)
- **Max concurrent transfers**: 1 transfer at a time (upload OR download) — enforced by shared `transferSemaphore`
- **Max retries**: 3 attempts per file (prevents infinite retry loops)
- **Dedup TTL**: 30 seconds (prevents duplicate advertisement processing)
- **Probe cooldown**: 30 seconds (per-device+file, allows re-probing when peers return to range)
- **Download timeout**: 60 minutes per file
- **BLE timeouts**: 90s per meta read attempt; fetchFile timeout is 60s + size-based (expectedSize * 1000 / 5000 ms, worst-case 5 KB/s)
- **Payload encryption**: AES-256-GCM encrypts compressed bytes; the AES key is wrapped with the originator's RSA private key and recovered using the QR-provided RSA public key.
- **No signatures**: Authenticated encryption replaces the former signature field and verification flow.
- **Database reset**: Schema changes drop and recreate the database; backward-compatible migrations are not required.

## File Structure

- `app/src/main/java/com/pyramidrelay/` — all source code (flat, no subdirectories for services)
- `app/src/test/java/com/pyramidrelay/` — unit tests
- `docs/play-console.md` — Play Console submission prep (Data Safety, FGS declaration, video script)

## Key Components

- **BlePeripheralService**: GATT server with extended advertising — one advertising set per file, all files advertise simultaneously (no rotation). Each set carries the full META payload as service data (up to 255B on LE 1M PHY non-legacy).
- **BleCentralService**: GATT client that connects, reads META, then streams file via notifications
- **SyncEngine**: Orchestrates scanning, advertising, and transfers; tracks download/upload progress; manages Bluetooth state
- **BroadcastsViewModel**: Manages broadcasts (Role.ORIGINATOR only shown in UI)
- **SubscriptionsViewModel**: Manages subscriptions; downloads trigger relay (Role.RELAY)
- **CryptoService**: Generates RSA keys and handles the hybrid encrypted payload envelope.
- **NotificationService**: Shows foreground notification for relaying and update notifications for file transfers.
- **BootReceiver**: Restarts the BLE foreground service on device boot (service only — never launches UI from background).

## Progress Tracking

- **Download progress**: `SyncEngine.downloadProgress` — `Map<String, Float>` (fileId → 0.0-1.0)
- **Upload progress**: `SyncEngine.streamingProgress` — `Map<String, Float>` (fileId → 0.0-1.0)
- Progress is determinate (shows actual bytes transferred vs total)
- Upload progress caps at 99% during transfer, switches to indeterminate indicator in UI

## Transfer Coordination

- **Shared `transferSemaphore`** (Semaphore(1)) between SyncEngine and BlePeripheralService ensures only one transfer at a time
- **`activeDownloadPeers`** and **`activeUploadPeers`** concurrent sets for cross-component coordination
- **`onStreamArmed`** callback: BlePeripheralService → SyncEngine calls `stopAdvertisingAndScanning()` immediately when a stream is armed
- **`onTransferStart`/`onTransferEnd`** callbacks: BlePeripheralService → SyncEngine stops/resumes advertising+scanning
- **`onTransferEnd`**: Resumes advertising and scanning after an upload completes; `transferSemaphore` prevents concurrent uploads and downloads
- **Download completion**: Resumes advertising and scanning after a download completes
- Download paths wait while `activeUploadPeers.contains(deviceAddress)` before acquiring transferSemaphore
- **`cancelTransfer(fileId)`**: Cancels active download jobs and stops uploads for a file
- **Periodic scan/GATT restart skipped during transfers**: Checks `_downloadingFileIds.value.isNotEmpty() || activeUploadPeers.isNotEmpty()`

## Bluetooth State Handling

- **BroadcastReceiver** listens for `BluetoothAdapter.ACTION_STATE_CHANGED`
- **STATE_OFF**: Cancels all active transfers, stops scanning and advertising
- **STATE_ON**: Restarts GATT server, scanning, and re-adverts all broadcasts

## Gotchas

- `BlePeripheralService` GATT server: `PERMISSION_READ` must be set on characteristics or reads fail with `GATT_READ_NOT_PERMITTED` (status=2)
- `BleCentralService.fetchFile`: uses `TRANSPORT_LE` for Samsung compatibility
- `CryptoService.keyId()` is defensive (returns 4 zero-bytes on malformed key)
- `publicKeyToBase64` uses URL-safe base64; `publicKeyFromBase64` accepts both URL-safe and standard
- **Hybrid encryption envelope**: Uses AES-256-GCM for compressed payloads and RSA private-key wrapping/public-key recovery; relays forward `file.encrypted` without decrypting it
- `BleForegroundService` acquires partial wake lock — release in `onDestroy()`
- **Buffer flush on disconnect**: Received data is written to file even if transfer is interrupted
- **Periodic scan restart**: BLE scan restarts every 5 minutes to fix Samsung BLE stack dropping service data
- **Periodic GATT server restart**: Every 5 minutes to fix META characteristic not found (skipped during active streams)
- **Atomic download guard**: Uses Mutex + Semaphore to prevent concurrent downloads for same fileId
- **Cancel transfers disconnects GATT**: `cancelTransfer` calls `disconnectDevice()` and `gattServer?.cancelConnection()` to notify receivers
- **GZIP compression**: Files compressed before encryption, decrypted and decompressed on receipt, cached at `file.compressed` and `file.encrypted`
- **Meta read retries**: 5 attempts (Samsung BLE connections frequently fail on first attempt)
- **Meta read timeout**: 90s per attempt
- **Delete cancels transfers**: `deleteBroadcast` and `deleteSubscription` call `cancelTransfer` first

## EventLog

Mirrors to logcat tag `EventLog` for remote debugging:
```bash
adb logcat -s EventLog:V
```

Key log prefixes:
- `[ble]` — BLE operations (streaming, downloading, connections)
- `[sync]` — Transfer orchestration (start, finish, retry)
- `[scan]` — Scan matching and discovery state
- `[adv]` — Advertising operations
- `[sub]` — Subscription operations
- `[app]` — Application-level events

## Test Devices

- `R52X104ZSRD` — Samsung device (primary test device)
- `R5CY91WAZGB` — Samsung device (secondary test device, may USB-disconnect during ADB)

## Opencode Skills

Project-specific skills are defined in `.opencode/skills/`:

- **run-tests**: Run all unit tests via `./gradlew testDebugUnitTest`
- **deploy**: Build debug APK and install on all connected Android devices
