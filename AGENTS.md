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
- **16-bit service UUID**: `0000f47b-0000-1000-8000-00805f9b34fb` — must fit in 31B legacy advertising packet
- **GATT-only transfer**: Central reads chunks from peripheral's STREAM characteristic (512B per chunk)
- **Samsung BLE quirk**: The BLE stack silently drops `onCharacteristicRead` callbacks when under heavy load. This is a known issue — notification-based streaming was attempted but had CCCD race conditions. Current read-per-chunk approach is ~99.6% reliable on Samsung devices.

## Key Constraints

- **compileSdk 35, minSdk 26, targetSdk 35**
- **Java 17** required (jvmToolchain)
- Unit tests use Robolectric + MockK
- `testOptions { unitTests.isReturnDefaultValues = true }` — Android framework methods return defaults in tests
- BLE operations require `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` permissions (Android 12+)
- Foreground service with partial wake lock required to keep BLE alive with screen off

## File Structure

- `app/src/main/java/p2p/broadcaster/` — all source code (flat, no subdirectories for services)
- `app/src/test/java/p2p/broadcaster/` — unit tests
- `docs/specifications.md` — detailed spec (278 lines)

## Gotchas

- `BlePeripheralService` GATT server: `PERMISSION_READ` must be set on characteristics or reads fail with `GATT_READ_NOT_PERMITTED` (status=2)
- `BleCentralService.fetchFile`: uses `TRANSPORT_LE` for Samsung compatibility, `gatt.refresh()` via reflection clears stale cache
- `CryptoService.keyId()` is defensive (returns 4 zero-bytes on malformed key)
- `publicKeyToBase64` uses URL-safe base64; `publicKeyFromBase64` accepts both URL-safe and standard
- `BleForegroundService` acquires partial wake lock — release in `onDestroy()`
- Spec §7: GATT transfer capped at 15 MB (5-min budget at ~50 KB/s)
