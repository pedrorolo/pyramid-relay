# Privacy Policy — Pyramid Relay

**Last updated:** 2026-09-08

Pyramid Relay is an offline-first, Bluetooth Low Energy (BLE) peer-to-peer file-sharing
app for Android. This policy explains what data the app handles, where it lives, and how
it is (and is not) shared.

---

## 1. The short version

- Pyramid Relay is designed to work **without an internet connection**. File transfers
  happen **device-to-device over BLE (Bluetooth Low Energy) only**.
- The app **does not operate a backend server** and does **not upload your files to the
  cloud** or any third party.
- Files you share are **compressed and encrypted on your device** before they are
  transmitted. The originator's **private** key never leaves your device; the **public**
  key is shared via the QR code / link so that peers can verify and decrypt the content.
- The app **does not itself collect, store, or transmit any personal information about
  you** off your device.
- If you install Pyramid Relay from **Google Play**, Google collects **crash and
  ANR (app-not-responding) diagnostics by default** via Android Vitals — this happens
  automatically, without any code in the app, for Play-distributed builds. See Section 7.
- One capability may be added **in the future** — in-app advertising — and that change
  would affect this policy. See Section 6. (A separate, developer-run crash reporter is
  also described in Section 7 as a possible future addition.)

---

## 2. How the app works (and what that means for your data)

Pyramid Relay lets you **broadcast** a file to nearby devices and **subscribe** to files
shared by others via a QR code or a `pyramidrelay://` link.

1. **Broadcasting.** You pick a file. The app generates an RSA keypair for that file,
   compresses the file, and encrypts it with a hybrid RSA/AES-GCM envelope. The encrypted
   payload is advertised to nearby peers over BLE. Only the **public** key is shared (via
   QR/link); the **private** key stays in your device's Android Keystore and is never
   transmitted.
2. **Subscribing.** You scan a QR code or paste a link containing the file ID and the
   public key. Your device then **advertises a "WANT" beacon** over BLE announcing that it
   is looking for that file. A peer that already has the file (and is scanning) discovers the
   beacon, connects over BLE, verifies the content against the public key, and pushes the
   encrypted file to you. Advertising this beacon is what lets you keep receiving updates even
   when your screen is off, because BLE advertising keeps working when Bluetooth scanning does
   not.
3. **Relaying.** Once a subscriber receives a file it can automatically re-advertise that
   same encrypted payload to other nearby peers (relay mode). Because a relay already
   holds the **public key** (it scanned the same QR code / link to subscribe), it **can
   decrypt and read** the file's contents. It **cannot modify or forge** new versions,
   because only the originator holds the private key — but confidentiality from relays and
   other subscribers is **not** provided. Relays simply forward the same encrypted payload
   they received.

Because every transfer is encrypted and happens directly between two Bluetooth devices,
**no file contents ever pass through Pyramid Relay's infrastructure** — there is no
infrastructure.

---

## 3. Data stored on your device

All data handled by the app stays in **app-private storage** on your phone:

| Data | Where it lives | Notes |
|------|---------------|-------|
| Files you broadcast or receive | App-private storage (`files/store/...`) | Stored encrypted at rest; only the latest version is kept per file |
| Broadcast & subscription metadata | Room database (app-private) | File IDs, versions, public keys, filenames |
| RSA private keys | Android Keystore | Hardware-backed where available; never exported |
| App logs | On-device only | Mirrored to logcat for local debugging; not uploaded |

This data is **not shared with anyone** by the app. Uninstalling the app removes it.

> **Backups:** The app currently allows Android Auto-Backup. If you use a cloud-backed
> device backup, a copy of the app's private data (including encrypted files and keys)
> may be included in *your* device backup, managed by your Android account/cloud
> provider — not by Pyramid Relay.

---

## 4. Data transmitted over Bluetooth (locally only)

When advertising or scanning, the app exchanges a small **metadata** payload over BLE.
Because **both broadcasters and subscribers advertise**, two kinds of beacon are broadcast:

- **HAVE beacon** (broadcasts and relays): announces that this device *has* a file.
- **WANT beacon** (subscriptions): announces that this device *wants* a file. This is what lets a
  screen-off or non-scanning peer still receive updates, because advertising survives when
  Bluetooth scanning does not.

Both beacons share the same metadata structure, and **neither beacon includes the file name**:

- the **full file ID** — a 16-byte UUID that uniquely identifies the file (not a truncated hash),
- the file **version** number,
- a **key identifier** (the first 4 bytes of the SHA-256 of the public key),
- a **device identifier** — a 16-byte UUID that is stable for your device, and
- (no file name — see below).

The file **name is never broadcast**. It is only exchanged after a Bluetooth connection is established:
the receiver learns it from the GATT **META characteristic** read (pull path) or from the **push header**
(push path). The subscriber already knows its own file name from the QR code / link, so omitting it from
the advertisement does not affect functionality.

The HAVE/relay beacon additionally carries the **SHA-256 hash of the file contents** (a 32-byte
content fingerprint) and the file size; the WANT beacon leaves those fields blank and instead carries
your device's *current* local version, so a peer holding a newer copy can push it to you.

What this means for your privacy:

- Anyone within Bluetooth range who runs a scanner can read these beacons. A **WANT beacon reveals
  that your device is interested in a specific file** (by its unique file ID and key identifier) — i.e.
  it exposes the *content you are looking for*, not only what you already have. The file **name** is not
  included in any beacon; it is only revealed to a connected peer during an actual transfer.
- The stable **device identifier** lets a nearby observer **link and track your device** across time
  and locations, and correlate the files you have and want.
- The file **contents themselves are never placed in the advertisement**. Only the encrypted
  envelope is transferred, and only after a GATT connection is established (see Section 2).

These signals travel only between nearby Bluetooth devices and are not routed through any server or
the internet.

### Permissions the app uses

- **Bluetooth (scan / connect / advertise):** required to find peers and transfer files.
- **Location (approximate, Android 8–11 only):** the app never determines or records
  your position. On Android 12+ no location permission is requested (`BLUETOOTH_SCAN`
  with `neverForLocation` suffices). On Android 8–11 the OS refuses Bluetooth scans
  without an approximate-location permission, so `ACCESS_COARSE_LOCATION`
  (`maxSdkVersion=30`) is requested there solely to unlock scanning.
- **Camera:** used **only** to scan QR codes locally for subscribing. Images are processed
  on-device and are not stored or uploaded.
- **Notifications / wake lock / foreground service:** used to keep transfers alive and
  show progress.
- **Internet:** the app's own manifest declares **no** `INTERNET` permission and the
  app opens no network connections itself — file transfers are Bluetooth-only. (The
  merged APK still lists `INTERNET` because bundled support libraries contribute it:
  AndroidX profileinstaller and ML Kit's telemetry transport. No Firebase backend is
  configured, so that transport has nowhere to send anything.) On Play-distributed
  builds, Google Play Services itself (not this app) may use its own connection to
  upload Android Vitals crash/ANR diagnostics (see Section 7). In-app advertising, if
  added later (Section 6), would require network access and this policy would be
  updated first.

---

## 5. Data we do NOT collect today

Pyramid Relay currently:

- does **not** require an account or login;
- does **not** track you or build a profile;
- does **not** send analytics, usage metrics, or advertising identifiers;
- does **not** access your contacts, photos, microphone, or precise location;
- does **not** transmit any data to Pyramid Relay or any third party *under the app's own
  control*. (If installed via Google Play, Google additionally collects crash/ANR
  diagnostics by default — see Section 7.)

---

## 6. Planned change: Advertising

We may add **in-app advertising** in a future release to help support development. If and
when advertising is introduced:

- Third-party ad SDKs (for example Google AdMob or similar) may be integrated into the app.
- Those SDKs may collect data such as an **advertising identifier**, **device type**,
  **approximate location**, **network information**, and **interaction with ads**, as
  governed by the ad provider's own privacy policy.
- This section and the rest of the policy will be updated before advertising goes live,
  including which provider(s) are used and how to opt out (e.g. resetting/limiting the
  advertising ID in Android settings).
- No advertising data would be used to weaken the offline, encrypted nature of file
  transfers described above.

---

## 7. Crash reporting and diagnostics

### 7a. Google Play (Android Vitals) — collected by default

If you install Pyramid Relay from **Google Play**, crash and ANR (app-not-responding)
diagnostics are collected **automatically by Google** through **Android Vitals**. This
requires no code in the app and applies to all Play-distributed builds:

- **What is collected:** crash/ANR **stack traces (backtraces)**, **device make/model**,
  **Android version**, **app version/build ID**, and related performance/health signals
  (e.g. battery, startup time, RAM).
- **Who collects it:** Google, not Pyramid Relay. The developer views aggregated reports
  in the Google Play Console.
- **When it applies:** only for builds installed via the Play Store on devices with Google
  Play Services. Sideloaded/debug APKs are not reported this way.
- Crash reports do **not** intentionally include the contents of files you share or receive,
  your contacts, or other personal content. They may incidentally include non-personal
  diagnostic context needed to reproduce a fault.
- Individual users cannot opt out of Android Vitals per-app; it is part of the Play
  platform. Uninstalling the app stops further reporting from that device.

### 7b. Future in-app crash reporting (possible)

We may additionally add a **developer-run crash reporter** in a future release to help
debug the known instability of some Android BLE stacks. If and when this is introduced:

- The app may collect **crash backtraces / stack traces**, **device model**, **Android
  version**, and **app version** when the app crashes or misbehaves, and send them to a
  third-party crash-reporting service (for example Firebase Crashlytics, Sentry, or
  similar) or to a server operated by the developer.
- This section will be updated with the specific provider and a description of what is
  collected before the feature ships, along with how to disable it where possible.

---

## 8. Children's privacy

Pyramid Relay is not directed at children under 13 and does not knowingly collect
personal information from them. Because the app does not currently collect personal
information at all, no such data is processed regardless of age.

---

## 9. Your choices

- **Stop sharing:** deleting a broadcast or subscription immediately stops advertising and
  removes the associated local data.
- **Block content:** every subscription offers Report and Block actions. Blocking deletes
  local files immediately, stops relaying, and ensures the file is never fetched or
  forwarded again (see EULA §3).
- **Remove all data:** uninstalling the app deletes its app-private storage, database, and
  keys.
- **Limit data use:** you can limit advertising data (e.g. resetting/limiting the
  advertising ID in Android settings, once ads are added per Section 6). Crash/ANR
  diagnostics collected by Google Play (Section 7a) are part of the Play platform and
  cannot be opted out of per-app; uninstalling the app stops further reporting.

---

## 10. Data retention

- Broadcast files, received files, metadata, and RSA private keys are kept on your
  device until you delete the corresponding broadcast/subscription.
- Deleting a broadcast or subscription immediately stops advertising it and removes
  its local files, database rows, and (for originators) its Keystore key.
- Uninstalling the app removes all app-private storage, the database, and all keys.
  Android Auto-Backup copies (see Section 3) follow your device backup's own
  retention.
- The developer retains no user data because none is ever collected (Section 5).

---

## 11. Changes to this policy

We will update this policy if our data practices change — in particular before enabling
advertising (Section 6) or adding a developer-run crash reporter (Section 7b). The "Last
updated" date at the top reflects the most recent revision.

---

## 12. Contact

For questions about this privacy policy or to report abuse, please open an issue at
https://github.com/pedrorolo/pyramid-relay/issues.

*Pyramid Relay is provided as-is, as an offline peer-to-peer tool. You are responsible for
the content you choose to share and relay.*
