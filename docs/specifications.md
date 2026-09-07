# P2P File Broadcaster - Specification

> **Android-only** native app written in **Kotlin**. Min SDK 26 (Android 8.0), target SDK 35 (Android 15). Jetpack Compose UI, Room database, Kotlin coroutines + Flow. No React Native / Expo / cross-platform layer.
> Build via Gradle (`./gradlew assembleDebug`). Requires physical Android device with BLE + WiFi Direct.

---

## 1. Overview

Two-tab app (Broadcasts | Subscriptions) via Jetpack Navigation + BottomNavigationView:

- **Originator** picks a file (SAF `ACTION_OPEN_DOCUMENT`), generates `Ed25519` keypair, signs `SHA256(fileId || version || SHA256(file))`, copies file to **app-private isolated store** (`files/store/<fileId>/v<version>/file`), advertises via BLE in background with `version` (and `publicKey` pointer).
- **Subscriber** scans QR (`pyramidrelay://` deep link with `fileId` + `publicKey`), then **advertises a WANT beacon** and background-scans BLE; on discovery of a HAVE beacon with `advertisedVersion > localVersion` (or missing file), fetches over BLE GATT, verifies with the advertised `publicKey`, stores internally, shows local notification, then **relays** (re-advertises identically, no private key, only subscriptions screen). A peer that sees the WANT beacon and holds a newer version can also push the file directly to the subscriber (see §8/§10).
- Both roles keep **only latest verified version** internally. `Save` exports a copy to user-chosen filesystem location (does not affect internal store). Deleting a broadcast stops advertising immediately. Fully offline - no HTTPS.

## 2. Baseline Audit

- Empty Kotlin Android project, no dependencies beyond AndroidX core.
- No permissions declared yet, no BLE/WiFi Direct/crypto/notification setup.
- Single-activity Compose scaffold to replace with two-tab navigation.

## 3. Architecture

```
Single-activity (MainActivity) + Jetpack Navigation
 -> Fragments: BroadcastsFragment, SubscriptionsFragment
 -> ViewModels: BroadcastsViewModel, SubscriptionsViewModel
 -> Repositories: BroadcastRepository, SubscriptionRepository
 -> Database: Room (AppDatabase) @/data/db/
    Entities: BroadcastEntity, SubscriptionEntity
    DAOs: BroadcastDao, SubscriptionDao
 -> Services:
    CryptoService          @/services/CryptoService.kt       java.security Ed25519 + BouncyCastle + KeyStore
    FileService            @/services/FileService.kt          android.content Context.files + ParcelFileDescriptor
    BleCentralService      @/services/ble/BleCentralService.kt  BluetoothGatt + BluetoothLeScanner
    BlePeripheralService   @/services/ble/BlePeripheralService.kt BluetoothLeAdvertiser + GATT server
    WifiDirectService      @/services/wifi/WifiDirectService.kt   WifiP2pManager + ServerSocket
    SyncEngine             @/services/SyncEngine.kt           scan/advert/verify/transfer/relay
    NotificationService    @/services/NotificationService.kt   NotificationManager + NotificationCompat
 -> DI: Hilt (@HiltAndroidApp, @HiltViewModel, @Inject, @Singleton)
 -> UI: @/ui/broadcasts/BroadcastRow.kt, @/ui/subscriptions/SubscriptionRow.kt,
         @/ui/qr/QrDisplayDialog.kt, @/ui/qr/QrScanFragment.kt, @/ui/common/
```

Single GATT service `APP_SERVICE_UUID` for all filtering. The app uses **128-bit UUIDs** carried in
**extended advertising** (LE 1M PHY, non-legacy), so each beacon holds the full META payload:
`APP_SERVICE_UUID = 00006d38-0000-1000-8000-00805f9b34fb` (HAVE beacons) and
`APP_WANT_SERVICE_UUID = 00006d39-0000-1000-8000-00805f9b34fb` (WANT beacons). GATT characteristics
(META, INCOMING) stay 128-bit and never appear in the advertisement (see §8).

## 4. Data Model

```kotlin
// @/data/model/Broadcast.kt
data class Broadcast(
    val fileId: String,           // UUID.randomUUID().toString() 16B
    val fileName: String, val mimeType: String,
    val internalUri: String,      // files/store/<fileId>/v<version>/file
    val fileHash: String,         // SHA-256 hex
    val fileSize: Long, val version: Int,   // uint32, 1.. , ++ on Update
    val publicKey: String,        // base64 32B Ed25519
    val privateKeyAlias: String?, // Android KeyStore alias "sk_<fileId>", null on relay
    val signature: String,        // base64 64B over msg
    val role: Role,               // ORIGINATOR | RELAY
    val createdAt: Long, val updatedAt: Long
)

enum class Role { ORIGINATOR, RELAY }

// @/data/model/Subscription.kt
data class Subscription(
    val fileId: String, val publicKey: String, // from QR
    val fileName: String?, val localVersion: Int?, // null = not fetched
    val localUri: String?, val subscribedAt: Long,
    val lastSeenVersion: Int?, val lastSeenAt: Long?
)
// msg = utf8(fileId) || BE32(version) || hex(fileHash)
// sig = signDetached(msg, sk) ; verify(msg,sig,pk)
```

Room `@Entity` with `@PrimaryKey(fileId)`. Private keys stored in Android `KeyStore` (Ed25519 `KeyPair` with alias `sk_<fileId>`). Subscribe trusts `pk` from QR only (reject fork with different pk).

## 5. Crypto (Asymmetric, not Symmetric)

Spec says "symmetric" but describes asymmetric (private-only signer, public in ad, per-version signature). HMAC/symmetric would let any subscriber forge. **Ed25519** required.

- Android `KeyStore` does not natively support Ed25519 (only RSA/EC P-256). Use **BouncyCastle** (`org.bouncycastle:bcprov-jdk18on:1.78`) or **Tink** (`com.google.crypto.tink:tink-android:1.14.0`) for Ed25519 `KeyPairGenerator` + `Signature`.
- Store `KeyPair` in `AndroidKeyStore` via `KeyPairGenerator` with `KeyGenParameterSpec.Builder(alias, Purpose.SIGN or Purpose.VERIFY).setAlgorithmParameterSpec(Ed25519ParameterSpec)` (API 33+). Fallback: BouncyCastle `Ed25519KeyPairGenerator` + encrypt with `EncryptedSharedPreferences` (`androidx.security:security-crypto:1.1.0-alpha06`).
- `MessageDigest.getInstance("SHA-256")` for hashing. `SecureRandom` for CSPRNG.

```kotlin
// Key generation
fun generateKeyPair(alias: String): KeyPair { /* BouncyCastle Ed25519 or AndroidKeyStore API33+ */ }
fun sign(message: ByteArray, alias: String): ByteArray { /* Ed25519 signDetached */ }
fun verify(message: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean { /* Ed25519 verify */ }
fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
```

## 6. File Storage - Isolated Single-Version

App-private `Context.filesDir` (`/data/data/<pkg>/files/`). Each broadcast at `files/store/<fileId>/v<version>/file`.

```
files/store/<fileId>/v<version>/file
```

- **Pick->Import (Broadcast/Update):** `ACTION_OPEN_DOCUMENT` intent -> `ContentResolver.openInputStream(uri)` -> digest + sign -> `File(storeDir).mkdirs()` -> `inputStream.copyTo(internalFile.outputStream())` -> enforce `MAX_FILE_SIZE` (see §7) + `StatFs` quota check.
- **Receive (Subscriber):** socket -> `vN/.tmp` -> verify hash+sigs -> rename to `vN/file` -> db.update -> **delete** old. Atomic: write tmp then rename; on fail old retained.
- **Single-version retention (both roles):** On commit of `N+1`, `File("store/$fileId/v$N").deleteRecursively()` after verify+move+DB+ad restart. `keepLastN=1` always.
- **Save (Subscription):** `Save` button reads `internalUri` -> `ACTION_OPEN_DOCUMENT_TREE` intent -> `ContentResolver.openOutputStream(treeUri)` -> `internalFile.copyTo(outputStream)`. Internal retained. Exports are copies; later `Save` after update exports new version. Disabled when `localVersion===null`.
- **Delete:** Broadcast delete stops adv (`BlePeripheralService.stopAdvertising(fileId)`, remove from syncEngine), then `File(fileId).deleteRecursively()` + `KeyStore.deleteEntry("sk_$fileId")` + DB delete. Subscription delete similarly (no keystore entry).
- **Quota:** reject if `StatFs.availableBlocksLong * blockSize < fileSize + 10MB` margin.

## 7. Max File Size (5-min BLE GATT)

**Decision (option C): file transfer happens entirely over BLE GATT - Wi-Fi Direct is not used.** Real-world Android-to-Android GATT throughput is ~50-125 KB/s; assume a conservative **50 KB/s**, so a transfer must finish within 5 min -> **15 MB limit**. Enforce:

```kotlin
const val MAX_FILE_SIZE = 15L * 1024 * 1024 // 15728640
if (fileSize > MAX_FILE_SIZE) throw IllegalArgumentException("File too large for 5-minute GATT transfer (max 15MB)")
```

Transfer protocol: subscriber writes `PULL v<n>` to the STREAM characteristic, then reads chunks sequentially (`mtu-3` bytes per read, empty read = done). Sequential reads give natural backpressure; no MTU-size or notification-loss hazards. §9 (Wi-Fi Direct) is retained only as historical reference / possible future large-file path.

Stream 64KB chunks via `InputStream`/`OutputStream` to avoid OOM; show progress+ETA via `NotificationCompat.Builder.setProgress`.

## 8. BLE - Dual Advertising (HAVE + WANT)

The app uses **Bluetooth LE extended advertising** (LE 1M PHY, non-legacy) so each beacon can
carry the full META payload. **Both broadcasts and subscriptions are advertised**, as two beacon
types:

- **HAVE beacon** (`APP_SERVICE_UUID = 00006d38-0000-1000-8000-00805f9b34fb`): one advertising
  set per broadcast and per relay. Service data = `BleMetaPayload.toBytes()` (fileId 16B + version 4B +
  fileHash SHA-256 32B + size 4B + keyId 4B + deviceId 16B + nameLen 2B + fileName). **The file name is
  omitted from the advertisement** (set blank); it is only sent to a *connected* peer via the GATT META
  characteristic read or the push header, so the name never leaks to passive scanners.
- **WANT beacon** (`APP_WANT_SERVICE_UUID = 00006d39-0000-1000-8000-00805f9b34fb`): one advertising
  set per **subscription**. Same META structure, but `fileHash` is zeroed, `version` is the
  subscriber's *local* version, and the **file name is omitted**, so a peer holding a newer copy can
  discover the WANT and push without the file name leaking over the air.

Both beacons are controller-offloaded, so they keep broadcasting when the screen is off or the CPU
is Doze-throttled. This is the basis for **screen-off receiving**: a screen-off *recipient* still
advertises WANT, while the screen-on *sender* scans, matches the WANT, and pushes over GATT.

- **GATT server:** `APP_SERVICE_UUID` exposes `META_CHAR` (long read) and an **`INCOMING_CHAR`**
  (`5b9d1c3e-2f8a-4c5b-9a1e-7c3d2e1f0a9b`, `PROPERTY_WRITE`) used by a peer to push a file to a
  device that only advertised WANT. The push header is 62 bytes (fileId 16 + version 4 + keyId 4 +
  size 4 + fileHash 32 + nameLen 2) followed by the file name and the encrypted envelope, written in
  512-byte acknowledged chunks.
- **Scanner:** `BluetoothLeScanner.startScan(...)` **unfiltered** (some OEM stacks drop service data
  when filtered via `setServiceUuid`). On `onScanResult`, the service-data UUID selects HAVE vs
  WANT; the META is parsed and a match (WANT with a newer version we hold, or HAVE newer than our
  local version) triggers a GATT connection + transfer.
- **Peripheral:** `BluetoothLeAdvertiser.startAdvertisingSet(...)` — one advertising set per
  broadcast/relay (HAVE) and one per subscription (WANT), each with its own `AdvertisingSetCallback`
  for per-file start/stop.
- **Advertising-set budget:** the controller supports a limited number of concurrent advertising
  sets (the SDK here does not expose the exact count). `BlePeripheralService` enforces a
  self-adjusting cap (default 5): WANT ads are dropped when at capacity, the oldest WANT ad is
  evicted to make room for a higher-priority HAVE/relay ad, and the cap shrinks if the controller
  rejects a start with `ADVERTISE_FAILED_TOO_MANY_ADVERTISERS`. Deferred WANT ads are retried when a
  slot frees.
- **Background:** Android `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + persistent
  notification (or a best-effort regular service + wake lock when the user disables it) keeps BLE
  alive. A periodic scan/GATT restart (every 5 min) works around Samsung stacks dropping service
  data.

## 9. WiFi Direct / P2P

Android-only WiFi Direct (`WifiP2pManager`).

- **Discovery:** `wifiP2pManager.discoverPeers(channel,.ActionListener)` -> `WIFI_P2P_STATE_CHANGED_ACTION` broadcast -> `wifiP2pManager.requestPeers(channel, PeerListListener)`.
- **Connect:** `wifiP2pManager.connect(channel, config, ActionListener)` -> `WIFI_P2P_CONNECTION_CHANGED_ACTION` -> `wifiP2pManager.requestConnectionInfo(channel, ConnectionInfoListener)` -> `groupOwnerAddress` (typically `192.168.49.1`).
- **Transfer:** GO opens `ServerSocket(8988)`, client `Socket(goIp, 8988)`. Stream 64KB chunks. Protocol: 4B magic + 4B fileId length + fileId + 4B version + 4B chunk count + [4B chunk length + chunk]...
- **Group creation:** `wifiP2pManager.createGroup(channel, groupInfo, ActionListener)` for GO role.
- Runs in `WifiDirectService` foreground service to keep connection alive.

## 10. Sync/Relay Engine

`SyncEngine` (`@Singleton` via Hilt) started on app launch + foreground service:

1. Load broadcasts (originator+relay) + subscriptions from Room.
2. Advertise every broadcast/relay as a **HAVE** beacon **and** every subscription as a **WANT**
   beacon, while scanning for peers.
3. On discovery:
   - **HAVE** beacon with `version > localVersion` (or local missing): connect via GATT, read META,
     verify against the subscription's public key, fetch/stream the encrypted envelope, verify hash,
     commit to store, notify, evict old version.
   - **WANT** beacon whose `fileId` + `keyId` matches a file we hold at a **newer** version: connect
     as GATT client and **push** the file to that peer over the `INCOMING_CHAR` (the screen-off
     receive path).
4. Dedupe `fileId+version` for 30s, ignore `<= local`.

Relays (`role === RELAY`) share via SyncEngine but **UI only on Subscriptions** screen.

## 11. UI - Two Tables (Jetpack Compose)

Jetpack Navigation + `BottomNavigation` (Broadcasts | Subscriptions):

- **Broadcasts** `BroadcastsFragment`: `LazyColumn` of `Broadcast role=ORIGINATOR`. Row: name/version/size/status(advertising) + `Update` (SAF pick -> version++) + `Share` (QR dialog with `pyramidrelay://subscribe?fileId&pk=BASE64URL&name&v`) + `Delete` (stop adv + delete). `FloatingActionButton` `+ Broadcast`.
- **Subscriptions** `SubscriptionsFragment`: `LazyColumn` of `Subscription`. Row: fileId short/name, `localVersion vs lastSeen`, status(listening/downloading/ready/relaying) + `Scan QR` (CameraX + ML Kit barcode) + `Paste Link` (dialog) + `Save` (SAF export) + `Open` (`Intent.ACTION_VIEW`) + `Delete`. `Save` disabled until fetched. Relay status shown here, never in Broadcasts.
- Dialogs: `QrDisplayDialog` (ZXing `qrcode`), `QrScanFragment` (CameraX).

## 12. QR / Deep Link

Content `pyramidrelay://subscribe?fileId=UUID&pk=BASE64URL&name=...&v=1` (<2KB) offline only; no HTTPS.

- **Intent Filter** in `AndroidManifest.xml`:
```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.BROWSABLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="pyramidrelay" android:host="subscribe" />
</intent-filter>
```
- `MainActivity` handles `intent.data` -> extract query params -> navigate to `SubscriptionsFragment` with args.
- QR scan: CameraX `ImageAnalysis` + ML Kit `BarcodeScanning` (`com.google.mlkit:barcode-scanning:17.3.0`), parse URI -> navigate.

## 13. Save + Notification

- **Save:** per §6, export copy via `ACTION_OPEN_DOCUMENT_TREE`.
- **Notification** (`NotificationManager` + `NotificationCompat.Builder`):
  - Create `NotificationChannel("p2p_updates", "File Updates", IMPORTANCE_HIGH)`.
  - `notificationManager.notify(fileId.hashCode(), builder.build())` after commit in SyncEngine.
  - Foreground: immediate. Background: via foreground service notification.
  - `PendingIntent` -> `MainActivity` with `data=subscribe?fileId&...` -> tap navigates to Subscriptions.
  - Dedupe `lastNotifiedVersion`. Android 13+ `POST_NOTIFICATIONS` runtime permission.

## 14. Config & Permissions

`AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

`build.gradle.kts` dependencies:
```kotlin
dependencies {
    // Compose + Navigation
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    // BLE
    // Uses Android BluetoothLeAdvertiser + BluetoothGatt (AndroidX core, no extra dep)
    // WiFi Direct
    // Uses android.net.wifi.p2p.WifiP2pManager (Android framework, no extra dep)
    // Crypto
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.google.crypto.tink:tink-android:1.14.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // QR
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Camera (QR scan)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Notifications
    implementation("androidx.core:core-ktx:1.15.0")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // WorkManager (background fallback)
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
```

## 15. Phases

1. **Scaffold 1d:** Kotlin project setup, Gradle, Compose + Navigation tabs, Room entities/DAOs, Hilt DI, types, BouncyCastle Tink integration, `assembleDebug` on device.
2. **Crypto + Isolated Store 2d:** CryptoService (Ed25519 gen/sign/verify), FileService (pick/import/evict/save/export), Room DB (Broadcast/Subscription), 300MB gate, Share QR, Update evicts old. Unit tests: `CryptoServiceTest`, `FileServiceTest`.
3. **BLE 3-4d:** `BluetoothLeAdvertiser` peripheral (ServiceData 14B, one advertiser per fileId), `BluetoothLeScanner` central, GATT server (META_CHAR 152B), `nRF Connect` 21B adv check, 2-device foreground test.
4. **WiFi Direct 2-3d:** `WifiP2pManager` discovery/connect/createGroup, `ServerSocket(8988)` GO/Client, 64KB chunked transfer with progress, after GATT verified.
5. **Sync Relay + Eviction 2d:** `SyncEngine` singleton, both-roles single-version eviction, relay re-advertise, foreground service + `WorkManager` periodic fallback, Delete stops adv immediately.
6. **Subscriptions UX 1-2d:** CameraX + ML Kit QR scan, Paste Link dialog, Save via SAF, Open via `Intent.ACTION_VIEW`, notifications + tap navigate, permission request flows.
7. **Hardening 1-2d:** `./gradlew lint test`, 3-device matrix (A v1 -> B subscribes -> relays -> C via B with A off -> A v2 -> B/C notification + Save v2), assert `store/<fileId>/v1` absent on all, exported v1 retained, forged pk rejected, Doze/force-quit recovery, disk-full rejection, 300MB streaming.

## 16. Risks

- `BluetoothLeAdvertiser` not all devices support peripheral mode -> test on common OEMs early, graceful fallback to GATT server only.
- Extended advertising carries the full META payload (fileId + deviceId + fileName), but the controller
  limits the number of concurrent advertising sets. The app caps and self-adjusts (see §8); when many
  broadcasts + subscriptions are active, lower-priority WANT ads may be dropped until a slot frees.
- Advertising both HAVE and WANT beacons exposes, over the air, that a device *has* or *wants* specific
  files (with file name and a stable device identifier) — a privacy trade-off enabling screen-off
  receiving. See PRIVACY.md §4.
- WiFi Direct varies by OEM (especially Samsung, Xiaomi) -> defensive `WifiP2pManager` error handling, retry logic.
- OEM Doze/force-quit -> foreground service + `WorkManager` 15min periodic + `WAKE_LOCK`, accept delay.

## 17. Verification

```bash
./gradlew lint test           # lint + unit tests
./gradlew connectedAndroidTest  # instrumented tests on device
```

Unit tests: `CryptoServiceTest` (sign/verify roundtrip, wrong key rejection), `FileServiceTest` (import/evict/update removes old only on success, exported copy survives).

Manual: A v1 -> B subscribes via QR -> downloads + Save -> relays -> C via B (A off) -> A v2 -> B/C notification, Save v2, assert `store/<fileId>/v1` absent on all, exported v1 retained, forged pk rejected.
