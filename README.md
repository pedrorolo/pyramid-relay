# Pyramid Relay

![Vibe Coded](https://img.shields.io/badge/%F0%9F%8E%B5_Vibe_Coded-purple?style=for-the-badge)
![Platform](https://img.shields.io/badge/platform-Android-green?style=for-the-badge)
![Kotlin](https://img.shields.io/badge/language-Kotlin-7F52FF?style=for-the-badge)
![License](https://img.shields.io/badge/license-Proprietary-red?style=for-the-badge)

**An offline-first, peer-to-peer file-sharing app for Android — no Wi-Fi, no internet, no server.**

Pyramid Relay lets you broadcast files to nearby devices and subscribe to files shared by
others, all over **Bluetooth Low Energy (BLE)**. Subscribers automatically re-broadcast what
they receive, forming a self-healing **mesh** that scales across a room or a remote village
without any network infrastructure.

---

## Why Pyramid Relay?

- **Truly offline.** Transfers happen device-to-device over BLE GATT. There is no backend,
  no cloud, and no internet dependency.
- **Resilient mesh.** Every subscriber becomes a relay, so content propagates organically as
  more people join — latecomers still get the latest version.
- **Authenticated updates.** Each broadcast is protected by a per-file RSA key pair. Only the
  originator can publish new versions; relays and third parties cannot forge them.
- **Private by design.** Files are compressed and encrypted on your device before they leave
  it. The originator's private key never leaves the device.

## Use cases

- An event organizer broadcasts the programme schedule. Attendees subscribe via QR code; when
  the organizer pushes an update, every subscriber receives it and continues relaying it to
  others who arrive later.
- Families or communities in remote, disconnected locations use Pyramid Relay as local file
  infrastructure — sharing documents, updates, and media directly between devices.

## Features

- **Offline-first** — works without internet, even in crowded venues or remote areas
- **Broadcast** files from your device to anyone nearby
- **Subscribe** via QR code or share link (`pyramidrelay://…`)
- **Relay** — subscribers automatically re-advertise received files, extending range organically
- **Versioned updates** — push new versions; subscribers are notified and updated automatically
- **Encrypted** — files are compressed and sealed in a hybrid RSA/AES-GCM envelope before transfer

## How it works

### Broadcasting

1. Pick a file from storage.
2. The app generates a per-file RSA key pair and seals the file in a hybrid RSA/AES-GCM
   envelope (see [Architecture](#architecture)).
3. The encrypted payload is advertised to nearby peers over BLE GATT, in the background.
4. When a new version is published, subscribers are notified and receive the update
   automatically.

### Subscribing

1. Scan a QR code or paste a share link (which carries the file ID and the public key).
2. Your device **advertises a "WANT" beacon** over BLE and also scans for peers. A peer that
   already has the file discovers your beacon (or you discover its broadcast), connects over BLE
   GATT, and pushes the encrypted file to you — including when your screen is off, since BLE
   advertising survives while scanning does not.
3. If a newer version is available, the file is downloaded and verified.
4. The file is saved and automatically re-advertised to other peers (relay mode).

The QR code / link acts as a capability key: it contains the file ID and the public key needed
to connect to any relaying peer, verify the payload, and decrypt its contents.

### The relay mesh

Once a subscriber receives a file, it becomes a relay, re-advertising the same encrypted
payload to other nearby peers. Because a relay holds the public key, it can decrypt and read
the content — but it **cannot forge or modify** new versions, since only the originator holds
the private key. Relays forward exactly what they received; they never re-encrypt or alter it.

Pyramid Relay does **not** perform blind relay: a device only re-advertises content it has
explicitly opted into by scanning a QR code or opening a share link, keeping users in control
of what they distribute.

## Architecture

Pyramid Relay is a single-activity Jetpack Compose app. The transfer layer is built entirely
on the Android BLE GATT stack:

- **`BlePeripheralService`** — GATT server. Advertises two kinds of beacon — a **HAVE** beacon
  per broadcast/relay (service UUID `…6d38`) and a **WANT** beacon per subscription
  (service UUID `…6d39`) — each carrying a small META payload as service data. It also exposes an
  **INCOMING** characteristic so a peer can push a file to a device that only advertised WANT (e.g.
  screen off), and streams the encrypted file to connected peers via notifications.
- **`BleCentralService`** — GATT client. Connects, reads META, and streams the file with
  credit-based flow control and **resumable** transfers (a stalled download resumes from the
  last received byte).
- **`SyncEngine`** — orchestrates scanning, advertising, transfers, and the relay logic, and
  tracks progress.
- **`CryptoService`** — generates the RSA keys and builds the hybrid envelope. Files are
  compressed (GZIP), then encrypted with **AES-256-GCM**; the AES key is wrapped with the
  originator's RSA private key and recovered by peers using the QR-provided RSA public key.
- **`NotificationService`** — foreground notification that keeps transfers alive with the screen
  off, plus per-file transfer updates.
- **`BootReceiver`** — restarts the BLE foreground service after device boot.

All broadcasts and subscriptions are stored locally in a **Room** database; private keys live
in the Android **Keystore**.

## Tech stack

| Area            | Choice                                              |
|-----------------|-----------------------------------------------------|
| Language / UI   | Kotlin, Jetpack Compose                             |
| Transfer        | BLE GATT (primary), foreground service + wake lock  |
| Crypto          | RSA + AES-256-GCM hybrid envelope                  |
| Storage         | Room database, Android Keystore                     |
| QR scanning     | CameraX + ML Kit Barcode Scanning                   |
| Min / Target    | Min SDK 26 (Android 8.0), Target/Compile SDK 35     |
| Toolchain       | Gradle, Java 17 (`jvmToolchain`)                   |

## Building

Requires the Android SDK and a physical Android device with BLE (emulators do not support
BLE). Connect the device over ADB, then:

```bash
./gradlew assembleDebug
```

## Testing

```bash
./gradlew testDebugUnitTest          # Unit tests (Robolectric + MockK)
./gradlew connectedDebugAndroidTest  # Instrumented tests on a connected device
```

## Privacy & licensing

- **Privacy:** See [PRIVACY.md](PRIVACY.md) for how the app handles (and does not handle) your
  data, including Google Play's default crash diagnostics.
- **License:** The original source is **proprietary** — see [LICENSE](LICENSE). Third-party
  open-source components are listed in [NOTICE.md](NOTICE.md) and viewable in-app under
  *Settings → Documentation → Open Source Licenses*.

## Contributing

This project is currently a personal build. Suggestions and issues are welcome via the GitHub
issue tracker.
