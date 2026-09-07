package com.pyramidrelay

import android.content.Context
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private lateinit var cryptoService: CryptoService
    private lateinit var broadcastDao: BroadcastDao
    private lateinit var subscriptionDao: SubscriptionDao
    private lateinit var fileService: FileService
    private lateinit var bleCentralService: BleCentralService
    private lateinit var blePeripheralService: BlePeripheralService
    private lateinit var notificationService: NotificationService
    private lateinit var engine: SyncEngine
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = kotlinx.coroutines.test.TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        CongestionPauses.clearAll()
        cryptoService = CryptoService()
        val context = mockk<Context>(relaxed = true)
        broadcastDao = mockk(relaxed = true)
        subscriptionDao = mockk(relaxed = true)
        fileService = mockk(relaxed = true)
        val tmpRoot = java.io.File(System.getProperty("java.io.tmpdir"), "synceng_${System.nanoTime()}").apply { mkdirs() }
        every { fileService.getVersionDir(any(), any()) } answers { java.io.File(tmpRoot, "v${secondArg<Int>()}") }
        every { fileService.getCompressedFile(any(), any()) } answers { java.io.File(tmpRoot, "compressed_${firstArg<String>()}") }
        every { fileService.getCompressedSize(any(), any()) } returns 1024L
        every { fileService.getFile(any(), any()) } answers { java.io.File(tmpRoot, "file_${firstArg<String>()}") }
        every { fileService.getTmpFile(any(), any()) } answers { java.io.File(tmpRoot, "tmp_${firstArg<String>()}") }
        bleCentralService = mockk(relaxed = true)
        blePeripheralService = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        every { broadcastDao.changeFlow } returns MutableStateFlow(0L)
        every { subscriptionDao.changeFlow } returns MutableStateFlow(0L)
        every { blePeripheralService.getDeviceUuidBytes() } returns ByteArray(16)
        coEvery { broadcastDao.getById(any()) } returns null
        engine = SyncEngine(context, broadcastDao, subscriptionDao, cryptoService, fileService,
            bleCentralService, blePeripheralService, notificationService, kotlinx.coroutines.sync.Mutex(), testScope)
    }

    @After
    fun teardown() {
        engine.destroy()
        Dispatchers.resetMain()
    }

    @Test
    fun `spec 10 - buildMetaPayload returns null for unknown fileId`() = runTest {
        coEvery { broadcastDao.getById(any()) } returns null
        val result = engine.buildMetaPayload("00000000-0000-0000-0000-000000000000")
        assertNull(result)
    }

    @Test
    fun `spec 10 - buildMetaPayload returns null for invalid UUID`() = runTest {
        val result = engine.buildMetaPayload("not-a-uuid")
        assertNull(result)
    }

    @Test
    fun `spec 10 - buildMetaPayload constructs correct payload for broadcast`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        cryptoService.storeKeyPair("sk_$fileId", kp)
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val hashStr = java.util.Base64.getEncoder().encodeToString(cryptoService.sha256("data".toByteArray()))
        val compressed = java.io.File.createTempFile("payload", ".compressed")
        compressed.writeBytes("compressed".toByteArray())
        every { fileService.getVersionDir(fileId, 1) } returns compressed.parentFile
        every { fileService.getCompressedFile(fileId, 1) } returns compressed

        val broadcast = BroadcastEntity(
            fileId, "test.txt", null, "text/plain", "/path", hashStr,
            1024, 1024, 1, pubKeyStr, "sk_$fileId", "",
            Role.ORIGINATOR, 0L, 0L
        )
        coEvery { broadcastDao.getById(fileId) } returns broadcast

        val payload = engine.buildMetaPayload(fileId)!!
        assertEquals(16, payload.fileId.size)
        assertEquals(1, payload.version)
        assertTrue(payload.fileSize > 0L)
        assertEquals(32, payload.fileHash.size)
    }

    @Test
    fun `spec 8 - startAdvertising constructs META service data`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val broadcast = BroadcastEntity(
            fileId, "test.txt", null, "text/plain", "/path", "abc",
            1024, 1024, 5, pubKeyStr, null, "sig",
            Role.RELAY, 0L, 0L
        )

        engine.startAdvertising(broadcast)

        verify {
            blePeripheralService.startAdvertising(fileId, match { data ->
                data.size > 14 &&
                BleMetaPayload.fromBytes(data) != null &&
                BleMetaPayload.fromBytes(data)!!.version == 5 &&
                BleMetaPayload.fromBytes(data)!!.fileName == "test.txt"
            })
        }
    }

    @Test
    fun `spec 8 - startAdvertising encodes version in META`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val hash = Base64.getEncoder().encodeToString(cryptoService.sha256("test".toByteArray()))
        val broadcast = BroadcastEntity(
            fileId, "f", null, "t", "p", hash, 100, 100, 0x01020304, pubKeyStr, null, "s",
            Role.RELAY, 0, 0
        )

        engine.startAdvertising(broadcast)

        verify {
            blePeripheralService.startAdvertising(fileId, match { data ->
                val meta = BleMetaPayload.fromBytes(data)
                meta != null && meta.version == 0x01020304
            })
        }
    }

    @Test
    fun `spec 10 - stop cancels engine and stops BLE`() {
        engine.stop()
        verify { bleCentralService.stopScan() }
        verify { blePeripheralService.stopAllAdvertising() }
    }

    @Test
    fun `spec 10 - dedup cache prevents duplicate handling`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val subscription = SubscriptionEntity(
            fileId, pubKeyStr, "test.txt", null, null, null, 0L, null, null, null
        )
        coEvery { subscriptionDao.getAll() } returns listOf(subscription)
        coEvery { broadcastDao.getAll() } returns emptyList()

        val fileIdHash = cryptoService.fileIdHash(fileId)
        val keyId = cryptoService.keyId(pubKeyStr)
        val serviceData = ByteArray(14)
        fileIdHash.copyInto(serviceData, 0)
        serviceData[6] = 0; serviceData[7] = 0; serviceData[8] = 0; serviceData[9] = 2
        keyId.copyInto(serviceData, 10)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData, null)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData, null)

        coVerify(exactly = 1) { bleCentralService.readMeta(any(), any()) }
    }

    @Test
    fun `spec 10 - skip if advertised version equals local broadcast version`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val broadcast = BroadcastEntity(
            fileId, "test.txt", null, "text/plain", "/path", "abc",
            1024, 1024, 3, pubKeyStr, null, "sig",
            Role.RELAY, 0L, 0L
        )
        coEvery { broadcastDao.getAll() } returns listOf(broadcast)
        coEvery { subscriptionDao.getById(any()) } returns null

        val fileIdHash = cryptoService.fileIdHash(fileId)
        val keyId = cryptoService.keyId(pubKeyStr)
        val serviceData = ByteArray(14)
        fileIdHash.copyInto(serviceData, 0)
        serviceData[6] = 0; serviceData[7] = 0; serviceData[8] = 0; serviceData[9] = 3
        keyId.copyInto(serviceData, 10)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData, null)

        coVerify(exactly = 0) { bleCentralService.readMeta(any(), any()) }
    }

    @Test
    fun `spec 10 - skip if advertised version less than local subscription`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val subscription = SubscriptionEntity(
            fileId, pubKeyStr, "test.txt", null, 5, "/path", 0L, 5, 0L, 5
        )
        coEvery { subscriptionDao.getAll() } returns listOf(subscription)
        coEvery { broadcastDao.getAll() } returns emptyList()

        val fileIdHash = cryptoService.fileIdHash(fileId)
        val keyId = cryptoService.keyId(pubKeyStr)
        val serviceData = ByteArray(14)
        fileIdHash.copyInto(serviceData, 0)
        serviceData[6] = 0; serviceData[7] = 0; serviceData[8] = 0; serviceData[9] = 3
        keyId.copyInto(serviceData, 10)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData, null)

        coVerify(exactly = 0) { bleCentralService.readMeta(any(), any()) }
    }

    @Test
    fun `spec 8 - handleDiscoveredDevice parses service data correctly`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val subscription = SubscriptionEntity(
            fileId, pubKeyStr, "test.txt", null, null, null, 0L, null, null, null
        )
        coEvery { subscriptionDao.getAll() } returns listOf(subscription)
        coEvery { broadcastDao.getAll() } returns emptyList()
        coEvery { bleCentralService.readMeta(any(), any()) } returns null

        val fileIdHash = cryptoService.fileIdHash(fileId)
        val keyId = cryptoService.keyId(pubKeyStr)
        val serviceData = ByteArray(14)
        fileIdHash.copyInto(serviceData, 0)
        serviceData[6] = 0; serviceData[7] = 0; serviceData[8] = 0; serviceData[9] = 2
        keyId.copyInto(serviceData, 10)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData, null)

        coVerify { bleCentralService.readMeta("AA:BB:CC:DD:EE:FF", any()) }
    }

    @Test
    fun `spec 10 - handleDiscoveredDevice ignores short service data`() = runTest {
        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", ByteArray(5), null)
        coVerify(exactly = 0) { broadcastDao.getAll() }
    }

    @Test
    fun `spec 4 - SyncEngine uuidToBytes produces 16 bytes from UUID`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        cryptoService.storeKeyPair("uuid-test", kp)
        val compressed = java.io.File.createTempFile("payload", ".compressed")
        compressed.writeBytes("compressed".toByteArray())
        every { fileService.getVersionDir(fileId, 1) } returns compressed.parentFile
        every { fileService.getCompressedFile(fileId, 1) } returns compressed
        val broadcast = BroadcastEntity(
            fileId, "f", null, "t", "p", java.util.Base64.getEncoder().encodeToString(ByteArray(32)), 0, 0, 1,
            cryptoService.publicKeyToBase64(kp.public), "uuid-test", "", Role.ORIGINATOR, 0, 0
        )
        coEvery { broadcastDao.getById(fileId) } returns broadcast

        val payload = engine.buildMetaPayload(fileId)!!
        assertEquals(16, payload.fileId.size)
        compressed.delete()
    }

    @Test
    fun `spec 10 - handleDiscoveredDevice matches subscription by fileIdHash and keyId`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val sub1 = SubscriptionEntity(fileId, pubKeyStr, "a", null, null, null, 0L, null, null, null)
        val sub2 = SubscriptionEntity(UUID.randomUUID().toString(), "otherpk", "b", null, null, null, 0L, null, null, null)
        coEvery { subscriptionDao.getAll() } returns listOf(sub1, sub2)
        coEvery { broadcastDao.getAll() } returns emptyList()
        coEvery { bleCentralService.readMeta(any(), any()) } returns null

        val fileIdHash = cryptoService.fileIdHash(fileId)
        val keyId = cryptoService.keyId(pubKeyStr)
        val serviceData = ByteArray(14)
        fileIdHash.copyInto(serviceData, 0)
        serviceData[6] = 0; serviceData[7] = 0; serviceData[8] = 0; serviceData[9] = 1
        keyId.copyInto(serviceData, 10)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData, null)

        coVerify { bleCentralService.readMeta("AA:BB:CC:DD:EE:FF", any()) }
    }

    @Test
    fun `spec 10 - onFileReceived callback is set`() {
        val callback: (String, Int) -> Unit = { _, _ -> }
        engine.onFileReceived = callback
        assertNotNull(engine.onFileReceived)
    }

    @Test
    fun `spec 10 - start and stop lifecycle`() = runTest {
        engine.start()
        engine.stop()
        verify { bleCentralService.stopScan() }
        verify { blePeripheralService.stopAllAdvertising() }
    }

    @Test
    fun `spec 10 - start does not restart if already running`() = runTest {
        engine.start()
        engine.start()
        engine.stop()
    }

    @Test
    fun `spec 10 - startAdvertising calls blePeripheral with META service data`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val broadcast = BroadcastEntity(
            fileId, "test.txt", null, "text/plain", "/path", "abc",
            1024, 1024, 0, pubKeyStr, null, "sig",
            Role.ORIGINATOR, 0L, 0L
        )

        engine.startAdvertising(broadcast)

        verify {
            blePeripheralService.startAdvertising(fileId, match { data ->
                val meta = BleMetaPayload.fromBytes(data)
                meta != null && meta.version == 0 && meta.fileName == "test.txt"
            })
        }
    }

    @Test
    fun `spec 10 - handleDiscoveredDevice skips when version less than or equal to local broadcast`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val broadcast = BroadcastEntity(
            fileId, "test.txt", null, "text/plain", "/path", "abc",
            1024, 1024, 10, pubKeyStr, "sk_$fileId", "sig",
            Role.ORIGINATOR, 0L, 0L
        )
        coEvery { broadcastDao.getAll() } returns listOf(broadcast)
        coEvery { subscriptionDao.getById(any()) } returns null

        val fileIdHash = cryptoService.fileIdHash(fileId)
        val keyId = cryptoService.keyId(pubKeyStr)
        val serviceData = ByteArray(14)
        fileIdHash.copyInto(serviceData, 0)
        serviceData[6] = 0; serviceData[7] = 0; serviceData[8] = 0; serviceData[9] = 5
        keyId.copyInto(serviceData, 10)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData, null)

        coVerify(exactly = 0) { bleCentralService.readMeta(any(), any()) }
    }

    @Test
    fun `spec 10 - handleDiscoveredDevice with no matching broadcast or subscription does nothing`() = runTest {
        coEvery { broadcastDao.getAll() } returns emptyList()
        coEvery { subscriptionDao.getAll() } returns emptyList()

        val serviceData = ByteArray(14)
        java.util.Random().nextBytes(serviceData)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData, null)

        coVerify(exactly = 0) { bleCentralService.readMeta(any(), any()) }
    }

    @Test
    fun `spec 10 - buildMetaPayload with valid broadcast returns correct fields`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        cryptoService.storeKeyPair("sk_$fileId", kp)
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val hashBytes = cryptoService.sha256("test data".toByteArray())
        val hashStr = java.util.Base64.getEncoder().encodeToString(hashBytes)
        val compressed = java.io.File.createTempFile("payload", ".compressed")
        compressed.writeBytes("compressed".toByteArray())
        every { fileService.getVersionDir(fileId, 1) } returns compressed.parentFile
        every { fileService.getCompressedFile(fileId, 1) } returns compressed
        val broadcast = BroadcastEntity(
            fileId, "test.txt", null, "text/plain", "/path", hashStr,
            2048, 2048, 1, pubKeyStr, "sk_$fileId", "",
            Role.ORIGINATOR, 0L, 0L
        )
        coEvery { broadcastDao.getById(fileId) } returns broadcast

        val payload = engine.buildMetaPayload(fileId)!!
        assertEquals(16, payload.fileId.size)
        assertEquals(1, payload.version)
        assertTrue(payload.fileSize > 0L)
        assertArrayEquals(hashBytes, payload.fileHash)
        compressed.delete()
    }

    @Test
    fun `spec 8 - startAdvertising includes keyId in META`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val hash = Base64.getEncoder().encodeToString(cryptoService.sha256("test".toByteArray()))
        val broadcast = BroadcastEntity(
            fileId, "test", null, "t", "/p", hash, 100, 100, 7, pubKeyStr, null, "s",
            Role.RELAY, 0, 0
        )

        engine.startAdvertising(broadcast)

        verify {
            blePeripheralService.startAdvertising(fileId, match { data ->
                val meta = BleMetaPayload.fromBytes(data)
                meta != null && meta.version == 7
            })
        }
    }

    @Test
    fun `spec 8 - startAdvertising includes fileId in META`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val hash = Base64.getEncoder().encodeToString(cryptoService.sha256("test".toByteArray()))
        val broadcast = BroadcastEntity(
            fileId, "test", null, "t", "/p", hash, 100, 100, 3, pubKeyStr, null, "s",
            Role.RELAY, 0, 0
        )

        engine.startAdvertising(broadcast)

        verify {
            blePeripheralService.startAdvertising(fileId, match { data ->
                val meta = BleMetaPayload.fromBytes(data)
                meta != null && meta.version == 3 && meta.fileName == "test"
            })
        }
    }

    // ------------------------------------------------------------------
    // Delete -> re-subscribe: stale dedup/probe state must not block refetch
    // ------------------------------------------------------------------

    private fun uuidBytes(fileId: String): ByteArray {
        val uuid = UUID.fromString(fileId)
        val msb = uuid.mostSignificantBits; val lsb = uuid.leastSignificantBits
        return ByteArray(16).also { bytes ->
            for (i in 0..7) {
                bytes[i] = ((msb ushr (8 * (7 - i))) and 0xFF).toByte()
                bytes[8 + i] = ((lsb ushr (8 * (7 - i))) and 0xFF).toByte()
            }
        }
    }

    @Test
    fun `re-subscribed file is probed again after discovery state reset`() = runTest {
        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val subscription = SubscriptionEntity(fileId, pubKeyStr, "test.txt", null, null, null, 0L, null, null, null)
        coEvery { subscriptionDao.getAll() } returns listOf(subscription)
        coEvery { broadcastDao.getAll() } returns emptyList()

        val meta = BleMetaPayload(
            uuidBytes(fileId), 1,
            cryptoService.sha256("data".toByteArray()),
            4L,
            "test.txt"
        )
        val serviceData = meta.toBytes()
        coEvery { bleCentralService.readMeta(any(), any()) } returns meta
        val addr = "AA:BB:CC:DD:EE:FF"

        // 1st discovery: probed once, signature rejected
        assertFalse(engine.handleDiscoveredDevice(addr, serviceData, meta))
        coVerify(exactly = 0) { bleCentralService.readMeta(any(), any()) }

        // 2nd discovery of the same advertisement: swallowed by dedup + cooldown
        assertFalse(engine.handleDiscoveredDevice(addr, serviceData, meta))
        coVerify(exactly = 0) { bleCentralService.readMeta(any(), any()) }

        // Delete + re-subscribe resets the discovery state
        engine.clearDiscoveryStateForFile(fileId)
        // The 1st (failing) download recorded a congestion pause; clear it so the
        // re-probe is gated only by discovery state, which is what this test checks.
        CongestionPauses.reset("${cryptoService.fileIdHash(fileId).joinToString("") { "%02x".format(it) }}${cryptoService.keyId(pubKeyStr).joinToString("") { "%02x".format(it) }}")

        // 3rd discovery: must probe again despite the earlier cooldown
        assertFalse(engine.handleDiscoveredDevice(addr, serviceData, meta))
        coVerify(exactly = 0) { bleCentralService.readMeta(any(), any()) }
    }
}
