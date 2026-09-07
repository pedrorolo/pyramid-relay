# Pyramid Relay

<img src="https://raw.githubusercontent.com/pedrorolo/pyramid-relay/refs/heads/main/docs/ic_launcher.svg" alt="Pyramid Relay" width="128"/>

![Vibe Coded](https://img.shields.io/badge/%F0%9F%8E%B5_Vibe_Coded-purple?style=for-the-badge)

> ⚠️ **Warning — BLE Stack Instability:** This app uses Android's BLE GATT stack extensively for file transfers. On some devices (particularly Samsung), prolonged or repeated BLE operations can render the OS Bluetooth stack into an inconsistent state. Symptoms include GATT operations timing out, connections being silently dropped, or the device failing to advertise/scan. When this happens, **a device reboot is required** to restore Bluetooth functionality. This is a known Android BLE stack limitation, not a bug in the app.

Bluetooth-based P2P file-sharing app for Android. Designed for **offline environments** — events with crowded networks, remote locations, or anywhere without internet access.

Share files and push updates to nearby devices via **BLE** (Bluetooth Low Energy) — no Wi-Fi or internet required. Subscribers automatically relay what they receive to other peers, creating a resilient mesh that scales without internet connectivity. Each broadcast is protected by a per-file Public Key Infrastructure (PKI): the originator's private key never leaves their device, and only the public key is shared via QR code or link. This ensures only the true originator can push updates — relays and third parties cannot forge new versions.

**Use cases:**
- An event organizer broadcasts the programme schedule — attendees subscribe via QR code, and when the organizer pushes an update, all subscribers receive it and continue relaying it to others who join later.
- Families or communities in remote locations without internet can use Pyramid Relay as a local file sharing infrastructure, sharing documents, updates, and media directly between devices.

## Features

- **Offline-first** — works without internet, even in remote locations or crowded venues
- **Broadcast** files from your device to anyone nearby
- **Subscribe** via QR code or share link
- **Relay** — subscribers automatically re-advertise received files to other peers, extending range organically
- **Versioned updates** — push new versions of a broadcast; subscribers are notified and receive the update automatically
- **Encrypted** — files are compressed and encrypted with hybrid RSA/AES-GCM before transmission

## Installation

Download the latest APK from the [Releases](https://github.com/pedrorolo/pyramid-relay/releases/latest) page, transfer it to your Android device, and install it. You may need to enable "Install from unknown sources" in your device settings.

## How It Works

### Broadcasting

1. Pick a file from storage
2. App generates an RSA keypair and signs the file metadata
3. File is compressed and encrypted for transmission
4. App advertises via BLE GATT in the background
5. When a new version is published, all subscribers are notified and receive the update

### Subscribing

1. Scan a QR code or paste a share link
2. App connects via BLE GATT and reads file metadata
3. If a newer version is available, the file is downloaded and verified
4. File is saved and automatically re-advertised to other peers (relay mode)

Once a subscriber receives a file, they become a relay — re-advertising the content to other nearby peers. This creates a mesh network where files propagate organically as more people subscribe.

The QR code/link functions as a key — it contains the file ID and public key needed to connect and read the content from any relaying peer.

## Compliance Note

Pyramid Relay does **not** perform blind relay. Subscribers only re-advertise content they have explicitly received and opted into by scanning a QR code or following a share link. This design ensures users maintain full control over what they relay, helping avoid potential legal and compliance issues associated with automated content distribution.

## Tech Stack

- **Kotlin** with Jetpack Compose UI
- **BLE GATT** as the primary transfer mechanism
- **RSA/AES-GCM** hybrid encryption for file payloads
- **Room** database for broadcasts and subscriptions
- **Foreground service** keeps transfers alive with screen off
- **CameraX + ML Kit** for QR code scanning
- Min SDK 26 (Android 8.0), target SDK 35


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
