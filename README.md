# Pyramid Relay

P2P file-sharing app for Android. Broadcast files to nearby devices via BLE — no internet required.

## Features

- **Broadcast** files from your device to anyone nearby
- **Subscribe** via QR code or share link
- **Relay** — received files are automatically re-advertised to other peers
- **Encrypted** — files are compressed and encrypted with hybrid RSA/AES-GCM before transfer
- **Offline** — uses Bluetooth Low Energy (BLE) GATT; no Wi-Fi or internet

## Tech Stack

- **Kotlin** with Jetpack Compose UI
- **BLE GATT** as the primary transfer mechanism
- **RSA/AES-GCM** hybrid encryption for file payloads
- **Room** database for broadcasts and subscriptions
- **Foreground service** keeps transfers alive with screen off
- **CameraX + ML Kit** for QR code scanning
- Min SDK 26 (Android 8.0), target SDK 35

## Project Structure

```
app/src/main/java/com/pyramidrelay/
├── BroadcastsScreen.kt     # Broadcast list UI
├── SubscriptionsScreen.kt  # Subscription list UI
├── MainActivity.kt         # Single-activity host
├── SyncEngine.kt           # Scan/advertise/transfer orchestration
├── BleCentralService.kt    # GATT client (download)
├── BlePeripheralService.kt # GATT server (upload/relay)
├── CryptoService.kt       # RSA/AES-GCM encryption
├── NotificationService.kt  # Foreground + update notifications
├── FileService.kt          # File pick, import, export, storage
├── QrDisplayDialog.kt     # QR code generation
├── QrScanDialog.kt        # QR code scanning
├── AppDatabase.kt         # Room database
├── Models.kt             # Data classes
└── SettingsScreen.kt      # App settings
```

## Building

```bash
./gradlew assembleDebug
```

Requires a physical Android device. Connect via ADB before building.

## Testing

```bash
./gradlew testDebugUnitTest       # Unit tests
./gradlew connectedAndroidTest    # Instrumented tests on device
```

## How It Works

### Broadcasting

1. Pick a file from storage
2. App generates an RSA keypair and signs the file metadata
3. File is compressed, encrypted, and stored privately
4. App advertises via BLE GATT in the background

### Subscribing

1. Scan a QR code or paste a share link
2. App connects via BLE GATT and reads file metadata
3. If a newer version is available, the file is downloaded and verified
4. File is saved and automatically relayed to other peers

### Transfer Protocol

- **BLE GATT** with 16-bit service UUID: `00006d38-0000-1000-8000-00805f9b34fb`
- **Metadata** read via GATT characteristic (152 bytes)
- **File chunks** pushed via BLE notifications (512 bytes per chunk, 10ms delay)
- **Hybrid encryption**: AES-256-GCM encrypts compressed payload; AES key wrapped with originator's RSA public key

## Permissions

- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`
- `CAMERA` — QR code scanning
- `FOREGROUND_SERVICE`, `WAKE_LOCK` — background transfers
- `POST_NOTIFICATIONS` — file update alerts
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — reliable background operation
- `RECEIVE_BOOT_COMPLETED` — restart service after reboot

## Deep Link Format

```
pyramidrelay://subscribe?fileId=<uuid>&pk=<base64url>&relayName=<name>&fileName=<filename>&v=<version>
```

- `fileId` — unique file identifier
- `pk` — originator's RSA public key (base64url encoded)
- `relayName` — optional human-readable label
- `fileName` — the file name (always included)
- `v` — file version number
