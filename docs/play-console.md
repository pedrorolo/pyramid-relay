# Play Console submission prep — Pyramid Relay

Fill these Console forms before submitting. Verify every answer against the
shipped APK; re-verify after any permission or SDK change.

## 1. Data safety section

Principle: the app transmits nothing off-device except over BLE to nearby
peers at the user's direction (broadcast / subscribe / relay). No servers, no
analytics, no ads (yet).

Suggested answers (confirm wording in the Console form):

- **Data collected or shared:** Location (approximate, Android 8–11 only, to
  enable BLE scanning; never leaves the device), Bluetooth device/file
  identifiers exchanged peer-to-peer, files the user chooses to share, crash
  logs via Play Vitals (collected by Google, not the app).
- **Collection is:** optional? No — core functionality; data is
  user-directed (the user picks files and scans QR codes to share).
- **Sharing:** peer-to-peer transfers initiated by the user; no third-party
  SDK sharing. (ML Kit / profileinstaller contribute no network traffic: no
  Firebase backend is configured.)
- **Security:** data in transit over BLE is AES-256-GCM encrypted;
  private keys in Android Keystore; no data sold.
- **Deletion:** no accounts exist; uninstall removes all data (PRIVACY.md §10).

If advertising (PRIVACY.md §6) or a crash reporter (§7b) is ever added, update
this section FIRST, then the privacy policy, then ship.

## 2. Permissions declarations

- **Location (coarse, maxSdkVersion=30):** declared for Android 8–11 only,
  where the OS requires it for BLE scanning. `neverForLocation`; position is
  never determined. No background-location declaration (no
  `ACCESS_BACKGROUND_LOCATION` requested).
- **Camera:** QR subscribe-code scanning, in-context request, on-device only.
- **Notifications:** transfer progress + foreground-service notice.
- **No SMS / contacts / storage / install-packages / accessibility.**

## 3. Foreground service declaration (`connectedDevice`)

- **Type:** `connectedDevice` (Bluetooth data transfer with external devices).
- **Functionality:** continuous BLE advertising, scanning, and file transfer
  so broadcasts propagate and subscriptions receive updates with the screen off.
- **If deferred:** transfers never start; nearby peers cannot discover files.
- **If interrupted:** in-progress transfers stall/fail and resume on restart;
  relay coverage gaps until the service restarts.
- **Use case:** "Continuous data transfer to an external device".
- **Video (record on a physical device, <2 min):**
  1. Open app, accept data disclosure, grant Bluetooth/notification permissions.
  2. Show the persistent "Relaying files" notification.
  3. Broadcast a file on device A; subscribe via QR on device B.
  4. Turn device B's screen off; show the file arriving anyway.
  5. Show relay status on device B for a third device.

## 4. Store listing checklist

- Category: Tools (or Communications); content rating questionnaire answered
  (user-shared files: rate for UGC capability).
- Privacy policy URL: public, non-geofenced, non-editable link to PRIVACY.md.
- Feature graphic / screenshots showing Broadcasts, Subscriptions, Settings →
  Privacy Policy (proves in-app policy access).
- Short description must not promise guaranteed emergency delivery; relay is
  best-effort and needs nearby peers with Bluetooth on.

## 5. Pre-submit verification

```bash
./gradlew assembleDebug
# merged manifest must NOT contain INTERNET:
grep -c 'android.permission.INTERNET' \
  app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml # expect 0
./gradlew testDebugUnitTest
```
