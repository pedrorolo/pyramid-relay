# P2P File Broadcaster - Specification

> **Android-only** native app written in **Kotlin**. Min SDK 26 (Android 8.0), target SDK 35 (Android 15). Jetpack Compose UI, Room database, Kotlin coroutines + Flow. No React Native / Expo / cross-platform layer.
> Build via Gradle (`./gradlew assembleDebug`). Requires physical Android device with BLE + WiFi Direct.

---

## 1. Overview

Two-tab app (Broadcasts | Subscriptions) via Jetpack Navigation + BottomNavigationView:

- **Originator** picks a file (SAF `ACTION_OPEN_DOCUMENT`), generates `Ed25519` keypair, signs `SHA256(fileId || version || SHA256(file))`, copies file to **app-private isolated store** (`files/store/<fileId>/v<version>/file`), advertises via BLE in background with `version` (and `publicKey` pointer).
- **Subscriber** scans QR (`pyramidrelay://` deep link with `fileId` + `publicKey`), background-scans BLE; on `advertisedVersion > localVersion` or missing file, fetches via WiFi Direct, verifies signature with advertised `publicKey`, stores internally, shows local notification, then **relays** (re-advertises identically, no private key, only subscriptions screen).
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

Single GATT service `APP_SERVICE_UUID` for all filtering. The UUID must be **16-bit** (`0000f47b-0000-1000-8000-00805f9b34fb`, alias `0xF47B`): a 128-bit UUID in a legacy 31B advertisement leaves only 10B for payload (see §8). GATT characteristics stay 128-bit - they never appear in the advertisement.

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

## 8. BLE - Pointer + GATT

Legacy adv 31B cannot fit `16+4+32+64=116B`. Hint pattern:

- **Adv 21B total:** `Flags 3B + ServiceData 0x16 AD structure 18B [1B len + 1B type + 2B UUID16 + 14B payload: fileIdHash6B=SHA256(fileId)[0:6] + version4B(BE32) + keyId4B=SHA256(pk)[0:4]]` under `APP_SERVICE_UUID`. (A 128-bit UUID would make the AD structure 32B -> 35B total -> `ADVERTISE_FAILED_DATA_TOO_LARGE`.) **One advertisement instance per broadcast** - 31B cannot hold two service-data entries, and each `startAdvertising` call is an independent advertiser (hardware limit, typically 3-5+, else `ADVERTISE_FAILED_TOO_MANY_ADVERTISERS`).
- **GATT Server:** `APP_SERVICE_UUID` -> `META_CHAR ~152B [fileId16|version4|pk32|sig64|fileHash32|size4` long read] + `INFO_CHAR`. Peripheral advertises, Central reads.
- **Scanner:** `BluetoothLeScanner.startScan(filters=[], settings=ScanSettings.SCAN_MODE_LOW_LATENCY)` - **unfiltered**, because some OEM stacks silently drop legacy advertisements carrying 16-bit-UUID service data when filtered via `setServiceUuid`. Match in code: onScanResult -> parse ServiceData under `APP_SERVICE_UUID` -> fileIdHash/keyId pre-filter -> `BluetoothGatt.connectGatt()` -> `discoverServices()` -> `readCharacteristic(META_CHAR)` -> verify -> `version > local`?
- **Peripheral:** `BluetoothLeAdvertiser.startAdvertising(settings, AdvertiseData.Builder().addServiceData(APP_SERVICE_UUID, serviceData14B).build(), callback)` - one call per broadcast fileId, each with its own `AdvertiseCallback` for per-file `stopAdvertising`.
- **Background:** Android `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + persistent notification, `SCAN_MODE_LOW_POWER`. Fallback `WorkManager` 15min periodic burst. Foreground-first banner in UI.

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
2. Advertise every authoritative file + scan for peers.
3. On discovery -> fileIdHash matches subscription/broadcast -> GATT meta read -> verify -> if `advertisedVersion > localVersion` (or null) -> WiFi Direct connect -> transfer stream -> verify hash+signature -> commit to store -> notify -> evict old version -> restart ad as relay if not originator.
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
- 31B adv limit -> hint+GATT verify pattern works but background interop unreliable, foreground-first banner.
- WiFi Direct varies by OEM (especially Samsung, Xiaomi) -> defensive `WifiP2pManager` error handling, retry logic.
- OEM Doze/force-quit -> foreground service + `WorkManager` 15min periodic + `WAKE_LOCK`, accept delay.

## 17. Verification

```bash
./gradlew lint test           # lint + unit tests
./gradlew connectedAndroidTest  # instrumented tests on device
```

Unit tests: `CryptoServiceTest` (sign/verify roundtrip, wrong key rejection), `FileServiceTest` (import/evict/update removes old only on success, exported copy survives).

Manual: A v1 -> B subscribes via QR -> downloads + Save -> relays -> C via B (A off) -> A v2 -> B/C notification, Save v2, assert `store/<fileId>/v1` absent on all, exported v1 retained, forged pk rejected.
