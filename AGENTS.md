# AGENTS.md — P2P File Broadcaster

## Project Overview

Android-only P2P file-sharing app. Kotlin, Jetpack Compose, BLE GATT for file transfer, Ed25519 PKI.

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
- **16-bit service UUID**: `0000f4b7-0000-1000-8000-00805f9b34fb` — fits in 31B legacy advertising packet (21B on wire)
- **Notification-based streaming**: Peripheral pushes chunks via `notifyCharacteristicChanged` (512B per chunk, 10ms sleep between chunks)
- **Samsung BLE quirk**: The BLE stack silently drops service data from scan results. Fixed with periodic scan restart (every 60s).

## Key Constraints

- **compileSdk 35, minSdk 26, targetSdk 35**
- **Java 17** required (jvmToolchain)
- Unit tests use Robolectric + MockK
- `testOptions { unitTests.isReturnDefaultValues = true }` — Android framework methods return defaults in tests
- BLE operations require `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` permissions (Android 12+)
- Foreground service with partial wake lock required to keep BLE alive with screen off
- **Battery optimization**: App checks at startup and prompts user to disable battery optimization for reliable transfers

## Transfer Limits

- **Max file size**: 20 MB (enforced at UI level in `BroadcastsViewModel`)
- **Max concurrent transfers**: 1 download + 1 upload at a time (Semaphore-based)
- **Max retries**: 3 attempts per file (prevents infinite retry loops)
- **Rotation interval**: 10 seconds (paused during active transfers)

## File Structure

- `app/src/main/java/p2p/broadcaster/` — all source code (flat, no subdirectories for services)
- `app/src/test/java/p2p/broadcaster/` — unit tests
- `docs/specifications.md` — detailed spec (278 lines)

## Key Components

- **BlePeripheralService**: GATT server with rotating advertising (single advertising set rotates through files)
- **BleCentralService**: GATT client that connects, reads META, then streams file via notifications
- **SyncEngine**: Orchestrates scanning, advertising, and transfers; tracks download/upload progress
- **BroadcastsViewModel**: Manages broadcasts (Role.ORIGINATOR only shown in UI)
- **SubscriptionsViewModel**: Manages subscriptions; downloads trigger relay (Role.RELAY)

## Progress Tracking

- **Download progress**: `SyncEngine.downloadProgress` — `Map<String, Float>` (fileId → 0.0-1.0)
- **Upload progress**: `SyncEngine.streamingProgress` — `Map<String, Float>` (fileId → 0.0-1.0)
- Progress is determinate (shows actual bytes transferred vs total)
- Upload progress caps at 99% during transfer, switches to indeterminate indicator in UI

## Gotchas

- `BlePeripheralService` GATT server: `PERMISSION_READ` must be set on characteristics or reads fail with `GATT_READ_NOT_PERMITTED` (status=2)
- `BleCentralService.fetchFile`: uses `TRANSPORT_LE` for Samsung compatibility
- `CryptoService.keyId()` is defensive (returns 4 zero-bytes on malformed key)
- `publicKeyToBase64` uses URL-safe base64; `publicKeyFromBase64` accepts both URL-safe and standard
- `BleForegroundService` acquires partial wake lock — release in `onDestroy()`
- **Buffer flush on disconnect**: Received data is written to file even if transfer is interrupted
- **Periodic scan restart**: BLE scan restarts every 60s to fix Samsung BLE stack dropping service data
- **Atomic download guard**: Uses Mutex + Semaphore to prevent concurrent downloads for same fileId
- **File size enforcement**: 20MB limit checked in UI before import (not in FileService)

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
- `R5CY91WAZGB` — Samsung device (secondary test device)
