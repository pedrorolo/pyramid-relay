# Privacy Policy — Pyramid Relay

**Last updated:** 2026-09-07

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
   public key. Your device connects to a nearby peer over BLE, verifies the content, and
   downloads the encrypted file.
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
This payload contains:

- a truncated **hash of the file ID** (so peers can match subscriptions),
- the file **version** number,
- a truncated **hash of the public key** (a key identifier), and
- the **encrypted file** itself during a transfer.

It does **not** contain your name, contacts, location, or the unencrypted contents of the
file. These signals travel only between nearby Bluetooth devices and are not routed
through any server or the internet.

### Permissions the app uses

- **Bluetooth (scan / connect / advertise):** required to find peers and transfer files.
- **Location (fine/coarse):** Android requires location permission for BLE scanning on
  older versions. Pyramid Relay requests it with `neverForLocation`, meaning it does not
  use Bluetooth scans to determine or record your location.
- **Camera:** used **only** to scan QR codes locally for subscribing. Images are processed
  on-device and are not stored or uploaded.
- **Notifications / wake lock / foreground service:** used to keep transfers alive and
  show progress.
- **Internet:** declared in the manifest but **not used by the app to send or receive file
  data** in the current version. On Play-distributed builds, Google Play Services may use
  the internet connection to upload Android Vitals crash/ANR diagnostics (see Section 7).
  In-app advertising, if added later (Section 6), would also use it.

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
- **Remove all data:** uninstalling the app deletes its app-private storage, database, and
  keys.
- **Limit data use:** you can limit advertising data (e.g. resetting/limiting the
  advertising ID in Android settings, once ads are added per Section 6). Crash/ANR
  diagnostics collected by Google Play (Section 7a) are part of the Play platform and
  cannot be opted out of per-app; uninstalling the app stops further reporting.

---

## 10. Changes to this policy

We will update this policy if our data practices change — in particular before enabling
advertising (Section 6) or adding a developer-run crash reporter (Section 7b). The "Last
updated" date at the top reflects the most recent revision.

---

## 11. Contact

For questions about this privacy policy, please open an issue on the project repository or
contact the maintainer through the project's GitHub page.

*Pyramid Relay is provided as-is, as an offline peer-to-peer tool. You are responsible for
the content you choose to share and relay.*
