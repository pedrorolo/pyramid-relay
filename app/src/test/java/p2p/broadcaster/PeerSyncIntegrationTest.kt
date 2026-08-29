package p2p.broadcaster

import io.mockk.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.OutputStream
import java.security.KeyPair
import java.util.Base64
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration tests for the peer data flow:
 *
 *   Device A (broadcaster)                       Device B (subscriber)
 *   ----------------------                       ---------------------
 *   BroadcastEntity (signed, PKI)                SubscriptionEntity (QR-scanned pk)
 *   startAdvertising -> 14B BLE adv    ------->  handleDiscoveredDevice parses adv
 *   buildMetaPayload -> GATT value     ------->  readMeta -> fromBytes -> verify sig
 *   FileService bytes -> GATT stream     ------->  fetchFile bytes -> hash check
 *                                                updateReceived + relay re-advertise
 *
 * The radio layers (BLE scan/advertising stacks, Wi-Fi Direct sockets) are stubbed,
 * but every byte crossing the stub is produced/consumed by the real code paths,
 * including full GATT payload serialization and real crypto verification.
 */
class PeerSyncIntegrationTest {

    // --- Device A (originator / broadcaster) ---
    private lateinit var cryptoA: CryptoService
    private lateinit var fileDirA: File
    private lateinit var fileServiceA: FileService
    private lateinit var broadcastDaoA: BroadcastDao
    private lateinit var subscriptionDaoA: SubscriptionDao
    private lateinit var bleCentralA: BleCentralService
    private lateinit var blePeripheralA: BlePeripheralService
    private lateinit var wifiDirectA: WifiDirectService
    private lateinit var notifierA: NotificationService
    private lateinit var engineA: SyncEngine

    // --- Device B (subscriber) ---
    private lateinit var cryptoB: CryptoService
    private lateinit var fileDirB: File
    private lateinit var fileServiceB: FileService
    private lateinit var broadcastDaoB: BroadcastDao
    private lateinit var subscriptionDaoB: SubscriptionDao
    private lateinit var bleCentralB: BleCentralService
    private lateinit var blePeripheralB: BlePeripheralService
    private lateinit var wifiDirectB: WifiDirectService
    private lateinit var notifierB: NotificationService
    private lateinit var engineB: SyncEngine

    // Captured advertisements leaving each device ("the air")
    private val advertisementsA = mutableMapOf<String, ByteArray>()
    private val advertisementsB = mutableMapOf<String, ByteArray>()

    @Before
    fun setUp() {
        cryptoA = CryptoService()
        cryptoB = CryptoService()
        fileDirA = File(System.getProperty("java.io.tmpdir"), "peer_a_${System.nanoTime()}").apply { mkdirs() }
        fileDirB = File(System.getProperty("java.io.tmpdir"), "peer_b_${System.nanoTime()}").apply { mkdirs() }

        fileServiceA = makeFileService(fileDirA)
        fileServiceB = makeFileService(fileDirB)

        // Device A transport stubs
        bleCentralA = mockk(relaxed = true)
        blePeripheralA = mockk(relaxed = true)
        wifiDirectA = mockk(relaxed = true)
        every { blePeripheralA.startAdvertising(any(), any()) } answers {
            advertisementsA[firstArg()] = secondArg()
        }

        // Device B transport stubs
        bleCentralB = mockk(relaxed = true)
        blePeripheralB = mockk(relaxed = true)
        wifiDirectB = mockk(relaxed = true)
        every { blePeripheralB.startAdvertising(any(), any()) } answers {
            advertisementsB[firstArg()] = secondArg()
        }

        broadcastDaoA = mockk(relaxed = true)
        subscriptionDaoA = mockk(relaxed = true)
        notifierA = mockk(relaxed = true)
        broadcastDaoB = mockk(relaxed = true)
        subscriptionDaoB = mockk(relaxed = true)
        notifierB = mockk(relaxed = true)

        engineA = SyncEngine(
            broadcastDaoA, subscriptionDaoA, cryptoA, fileServiceA,
            bleCentralA, blePeripheralA, wifiDirectA, notifierA
        )
        engineB = SyncEngine(
            broadcastDaoB, subscriptionDaoB, cryptoB, fileServiceB,
            bleCentralB, blePeripheralB, wifiDirectB, notifierB
        )
    }

    @After
    fun tearDown() {
        engineA.destroy()
        engineB.destroy()
        fileDirA.deleteRecursively()
        fileDirB.deleteRecursively()
    }

    private fun makeFileService(dir: File): FileService {
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.filesDir } returns dir
        return FileService(context)
    }

    /** Creates Device A's published state: keypair, physical file v2, signed BroadcastEntity. */
    private fun publishOnDeviceA(content: ByteArray): Pair<BroadcastEntity, KeyPair> {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoA.generateEd25519KeyPair()
        val pubKeyStr = cryptoA.publicKeyToBase64(kp.public)

        val version = 2
        val dir = fileServiceA.getVersionDir(fileId, version); dir.mkdirs()
        val file = fileServiceA.getFile(fileId, version)
        file.writeBytes(content)

        val hashHex = cryptoA.sha256Hex(content)
        val hashStr = Base64.getEncoder().encodeToString(cryptoA.sha256(content))
        val signature = cryptoA.sign(cryptoA.buildSignatureMessage(fileId, version, hashHex), kp.private)
        val sigStr = Base64.getEncoder().encodeToString(signature)

        val entity = BroadcastEntity(
            fileId, "spec.txt", "text/plain", file.absolutePath, hashStr,
            content.size.toLong(), version, pubKeyStr, "sk_$fileId", sigStr,
            Role.ORIGINATOR, System.currentTimeMillis(), System.currentTimeMillis()
        )
        coEvery { broadcastDaoA.getById(fileId) } returns entity
        return entity to kp
    }

    /** Creates Device B's subscribed state: knows publisher pk via QR but has no file yet. */
    private fun subscribeOnDeviceB(entity: BroadcastEntity) {
        val sub = SubscriptionEntity(
            entity.fileId, entity.publicKey, entity.fileName,
            null, null, System.currentTimeMillis(),
            null, null, null
        )
        coEvery { subscriptionDaoB.getAll() } returns listOf(sub)
        coEvery { broadcastDaoB.getAll() } returns emptyList()
    }

    /**
     * Simulated GATT read: B pulls the META characteristic value from A.
     * Bytes are serialized by A's payload builder and deserialized by B's central
     * callback logic, exactly as they cross the ATT layer - no object passing.
     */
    private suspend fun gattReadFromA(): BleMetaPayload? {
        val fileId = advertisementsA.keys.firstOrNull() ?: return null
        val bytes = engineA.buildMetaPayload(fileId)?.toBytes() ?: return null
        return BleMetaPayload.fromBytes(bytes) // this is what BleCentralService does on read
    }

    /**
     * Simulated GATT file transfer: B's fetchFile(address, version, expectedSize, out)
     * is satisfied from Device A's physical store through a plain stream pipe
     * (no parsing shortcuts).
     */
    private fun stubGattTransfer() {
        coEvery { bleCentralB.fetchFile(any(), any<Int>(), any<Long>(), any<OutputStream>(), any()) } answers {
            val version = arg<Int>(1)
            val out = arg<OutputStream>(3)
            val fileId = advertisementsA.keys.firstOrNull()
            if (fileId == null) false
            else {
                fileServiceA.readForTransfer(fileId, version).use { it.copyTo(out) }
                true
            }
        }
    }

    // ------------------------------------------------------------------
    // 1. Advertisement <-> listener agreement
    // ------------------------------------------------------------------

    @Test
    fun `advertisement emitted by broadcaster is parsed and accepted by subscriber`() = runBlocking {
        val content = "advertise-me".toByteArray()
        val entity = publishOnDeviceA(content).first
        subscribeOnDeviceB(entity)

        // Broadcaster emits its 14-byte service data onto "the air"
        engineA.startAdvertising(entity)
        assertEquals(1, advertisementsA.size)
        val airBytes = advertisementsA[entity.fileId]!!

        // Independent decode of exactly what left the radio
        val adv = BleAdvertisement.fromServiceData(airBytes)!!
        assertArrayEquals(cryptoA.fileIdHash(entity.fileId), adv.fileIdHash)
        assertEquals(entity.version, adv.version)
        assertArrayEquals(cryptoA.keyId(entity.publicKey), adv.keyId)
        // Round trip through the model must be lossless
        assertEquals(adv, BleAdvertisement.fromServiceData(adv.toServiceData()))

        // Subscriber picks it up; because the subscription matches fileIdHash+keyId
        // it must reach out via GATT for the meta payload.
        engineB.handleDiscoveredDevice("AA:BB:CC:DD:EE:01", airBytes)
        coVerify(exactly = 1) { bleCentralB.readMeta("AA:BB:CC:DD:EE:01", any()) }

        // An advertisement for an unknown fileId must NOT trigger a GATT read
        val strangerBytes = ByteArray(14)
        cryptoA.fileIdHash(UUID.randomUUID().toString()).copyInto(strangerBytes, 0)
        strangerBytes[6] = 0; strangerBytes[7] = 0; strangerBytes[8] = 0; strangerBytes[9] = 9
        cryptoA.keyId(entity.publicKey).copyInto(strangerBytes, 10)
        assertFalse(engineB.handleDiscoveredDevice("AA:BB:CC:DD:EE:02", strangerBytes))
        coVerify(exactly = 1) { bleCentralB.readMeta(any(), any()) }
    }

    // ------------------------------------------------------------------
    // 2. GATT meta payload <-> PKI agreement
    // ------------------------------------------------------------------

    @Test
    fun `gatt meta payload survives serialization and signature verifies against raw publisher key`() = runBlocking {
        val content = "meta-payload-check".toByteArray()
        val (entity, kp) = publishOnDeviceA(content)

        // Simulate the GATT transfer: serialize on A, deserialize on B
        val parsed = BleMetaPayload.fromBytes(engineA.buildMetaPayload(entity.fileId)!!.toBytes())!!

        assertEquals(entity.version, parsed.version)
        assertArrayEquals(cryptoA.sha256(content), parsed.fileHash)
        assertEquals(content.size.toLong(), parsed.fileSize)

        // Public key is not in META payload - use the subscription's public key
        val reconstructedPubKey = cryptoB.publicKeyFromBase64(entity.publicKey)
        val hashHex = parsed.fileHash.joinToString("") { "%02x".format(it) }
        val msg = cryptoB.buildSignatureMessage(entity.fileId, parsed.version, hashHex)
        assertTrue(cryptoB.verify(msg, parsed.signature, reconstructedPubKey))

        // Tampering with any payload region must break verification (PKI binding holds)
        val corruptSig = parsed.signature.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(cryptoB.verify(msg, corruptSig, reconstructedPubKey))
        val wrongHashMsg = cryptoB.buildSignatureMessage(entity.fileId, parsed.version, "deadbeef")
        assertFalse(cryptoB.verify(wrongHashMsg, parsed.signature, reconstructedPubKey))
    }

    // ------------------------------------------------------------------
    // 3. Full sync: discovery -> meta -> transfer -> database -> relay
    // ------------------------------------------------------------------

    @Test
    fun `full subscription sync delivers byte identical file and enables relay`() = runBlocking {
        val content = "The quick brown fox jumps over the lazy dog".repeat(100).toByteArray()
        val (entity, _) = publishOnDeviceA(content)
        subscribeOnDeviceB(entity)

        // Stub the GATT transfer: B reads META from A and fetches the file from A's store
        coEvery { bleCentralB.readMeta(any(), any()) } coAnswers { gattReadFromA() }
        stubGattTransfer()

        // Start advertising on A and capture the service data
        engineA.startAdvertising(entity)
        assertTrue("advertisementsA should contain the fileId", advertisementsA.containsKey(entity.fileId))
        val serviceData = advertisementsA[entity.fileId]!!
        assertTrue("serviceData should be at least 14 bytes", serviceData.size >= 14)

        // Debug: check subscription setup
        val subscriptions = subscriptionDaoB.getAll()
        assertEquals(1, subscriptions.size)
        val sub = subscriptions[0]
        assertEquals(entity.fileId, sub.fileId)
        assertEquals(entity.publicKey, sub.publicKey)

        // Debug: check fileIdHash matching
        val fileIdHash = cryptoB.fileIdHash(entity.fileId)
        val serviceDataFileIdHash = serviceData.copyOfRange(0, 6)
        assertTrue("fileIdHash should match", fileIdHash.contentEquals(serviceDataFileIdHash))

        // Act: B hears the advertisement and runs its whole receive pipeline
        val result = engineB.handleDiscoveredDevice("AA:BB:CC:DD:EE:04", serviceData)
        assertTrue("handleDiscoveredDevice should return true for a valid subscription match", result)

        // Database side: subscription advanced to v2 with a real location in B's store
        val updatedSlot = slot<Int>()
        val uriSlot = slot<String>()
        val nameSlot = slot<String>()
        coVerify(exactly = 1) {
            subscriptionDaoB.updateReceived(entity.fileId, capture(updatedSlot), capture(uriSlot), any(), any(), capture(nameSlot))
        }
        assertEquals(entity.version, updatedSlot.captured)
        assertEquals(entity.fileName, nameSlot.captured)

        // Physical side: the delivered file lives in B's store and is byte-identical
        val receivedFile = File(uriSlot.captured)
        assertTrue(receivedFile.exists())
        assertTrue(receivedFile.path.startsWith(fileDirB.path))
        assertArrayEquals(cryptoB.sha256(content), cryptoB.sha256(receivedFile.readBytes()))
        assertEquals(content.size.toLong(), receivedFile.length())

        // Old versions evicted on B (only v2 remains)
        assertFalse(fileServiceB.hasFile(entity.fileId, 1))

        // Subscriber now relays: advertises the very same (hash, version, keyId) triple
        val relayAdv = BleAdvertisement.fromServiceData(advertisementsB[entity.fileId]!!)
        assertNotNull(relayAdv)
        assertArrayEquals(cryptoA.fileIdHash(entity.fileId), relayAdv!!.fileIdHash)
        assertEquals(entity.version, relayAdv.version)
        assertArrayEquals(cryptoA.keyId(entity.publicKey), relayAdv.keyId)

        // User was notified about the new version
        coVerify(exactly = 1) {
            notifierB.showUpdateNotification(entity.fileName!!, entity.fileId, 0, entity.version)
        }
    }

    @Test
    fun `subscriber rejects meta whose embedded key does not match the QR-scanned key`() = runBlocking {
        val content = "evil-publisher".toByteArray()
        val (entity, _) = publishOnDeviceA(content)
        subscribeOnDeviceB(entity)
        engineA.startAdvertising(entity)
        stubGattTransfer()

        // Attacker signs correctly but with a DIFFERENT key than the subscribed one,
        // replaying the advertised shape for the known fileId.
        val attackerKp = cryptoB.generateEd25519KeyPair()
        val hashHex = cryptoB.sha256Hex(content)
        val attackerSig =
            cryptoB.sign(cryptoB.buildSignatureMessage(entity.fileId, entity.version, hashHex), attackerKp.private)
        val forgedPayload = BleMetaPayload(
            uuidToBytesUnchecked(entity.fileId), entity.version,
            attackerSig,
            cryptoB.sha256(content), content.size.toLong(),
            entity.fileName ?: "file"
        )
        coEvery { bleCentralB.readMeta(any(), any()) } returns forgedPayload

        assertFalse(engineB.handleDiscoveredDevice("AA:BB:CC:DD:EE:05", advertisementsA[entity.fileId]!!))
        coVerify(exactly = 0) { subscriptionDaoB.updateReceived(any(), any(), any(), any(), any(), any()) }
        assertTrue(advertisementsB.isEmpty()) // nothing got relayed either
    }

    @Test
    fun `subscriber rejects meta whose signature does not verify`() = runBlocking {
        val content = "bad-signature".toByteArray()
        val (entity, _) = publishOnDeviceA(content)
        subscribeOnDeviceB(entity)
        engineA.startAdvertising(entity)
        stubGattTransfer()

        // Correct publisher key, corrupted signature over the claimed version/hash
        val good = gattReadFromA()!!
        val bad = BleMetaPayload(
            good.fileId,
            good.version,
            good.signature.copyOf().also { it[10]++ },
            good.fileHash,
            good.fileSize,
            good.fileName
        )
        coEvery { bleCentralB.readMeta(any(), any()) } returns bad

        assertFalse(engineB.handleDiscoveredDevice("AA:BB:CC:DD:EE:06", advertisementsA[entity.fileId]!!))
        coVerify(exactly = 0) { subscriptionDaoB.updateReceived(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { bleCentralB.fetchFile(any(), any<Int>(), any<Long>(), any<OutputStream>()) }
    }

    @Test
    fun `stale advertisement versions are ignored without contacting the peer`() = runBlocking {
        val content = "old-version-guard".toByteArray()
        val (entity, _) = publishOnDeviceA(content)
        subscribeOnDeviceB(entity)

        // Say B already has v2 locally
        coEvery { subscriptionDaoB.getAll() } returns listOf(
            SubscriptionEntity(
                entity.fileId, entity.publicKey, entity.fileName,
                2, "/local/path", System.currentTimeMillis(), 2, System.currentTimeMillis(), null
            )
        )

        val stale = advertisementsA[entity.fileId] ?: run {
            engineA.startAdvertising(entity); advertisementsA[entity.fileId]!!
        }
        assertFalse(engineB.handleDiscoveredDevice("AA:BB:CC:DD:EE:07", stale))
        coVerify(exactly = 0) { bleCentralB.readMeta(any(), any()) }
        coVerify(exactly = 0) { bleCentralB.fetchFile(any(), any<Int>(), any<Long>(), any<OutputStream>()) }
    }

    // ------------------------------------------------------------------
    // 4. Wi-Fi Direct protocol: framing integrity over REAL sockets
    // ------------------------------------------------------------------

    @Test
    fun `wifi direct pull protocol delivers byte identical file over localhost`() {
        val context = mockk<android.content.Context>(relaxed = true)
        val server = WifiDirectService(context)
        val payload = ("wifi-direct-framing-" + "x".repeat(200_000)).toByteArray()

        server.onRequestFile = { fileId, _, out ->
            if (fileId != "frame-file") throw IllegalStateException("unknown")
            out.write(payload)
        }
        server.startServer()
        try {
            // Deterministic boot: wait until the accept-loop has actually bound the port
            runBlocking { assertTrue(server.awaitServerReady(5_000)) }
            // Retry connect while the async accept-loop boots
            val deadline = System.currentTimeMillis() + 10_000
            var received = ByteArray(0)
            val errors = mutableMapOf<String, Int>()
            while (System.currentTimeMillis() < deadline && received.isEmpty()) {
                val result = runCatching {
                    runBlocking {
                        val out = java.io.ByteArrayOutputStream()
                        if (!server.pullFile("127.0.0.1", "frame-file", 2, out)) error("transfer failed")
                        out.toByteArray()
                    }
                }
                if (result.isSuccess) received = result.getOrThrow()
                else {
                    val t = result.exceptionOrNull()!!
                    errors["${t.javaClass.simpleName}: ${t.message}"] =
                        (errors["${t.javaClass.simpleName}: ${t.message}"] ?: 0) + 1
                    Thread.sleep(100)
                }
            }
            assertTrue("expected transfer within timeout (errors=$errors)", received.isNotEmpty())
            assertEquals(payload.size.toLong(), received.size.toLong())
            assertArrayEquals(payload, received)
        } finally {
            server.stopServer()
        }
    }

    private fun uuidToBytesUnchecked(fileId: String): ByteArray {
        val uuid = UUID.fromString(fileId)
        val msb = uuid.mostSignificantBits;
        val lsb = uuid.leastSignificantBits
        return ByteArray(16).also { b ->
            for (i in 0..7) {
                b[i] = ((msb ushr (8 * (7 - i))) and 0xFF).toByte(); b[8 + i] =
                    ((lsb ushr (8 * (7 - i))) and 0xFF).toByte()
            }
        }
    }
}
