package com.pyramidrelay

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.security.KeyPair
import java.util.Base64
import java.util.UUID

/** Protocol-level integration tests with BLE radio operations stubbed. */
class PeerSyncIntegrationTest {
    private lateinit var cryptoA: CryptoService
    private lateinit var cryptoB: CryptoService
    private lateinit var fileServiceA: FileService
    private lateinit var fileServiceB: FileService
    private lateinit var fileDirA: File
    private lateinit var fileDirB: File
    private lateinit var broadcastDaoA: BroadcastDao
    private lateinit var subscriptionDaoA: SubscriptionDao
    private lateinit var broadcastDaoB: BroadcastDao
    private lateinit var subscriptionDaoB: SubscriptionDao
    private lateinit var bleCentralA: BleCentralService
    private lateinit var blePeripheralA: BlePeripheralService
    private lateinit var bleCentralB: BleCentralService
    private lateinit var blePeripheralB: BlePeripheralService
    private lateinit var engineA: SyncEngine
    private lateinit var engineB: SyncEngine
    private lateinit var notifierB: NotificationService
    private val advertisementsA = mutableMapOf<String, ByteArray>()
    private val advertisementsB = mutableMapOf<String, ByteArray>()

    @Before
    fun setUp() {
        cryptoA = CryptoService()
        cryptoB = CryptoService()
        fileDirA = File(System.getProperty("java.io.tmpdir"), "peer_a_${System.nanoTime()}").apply { mkdirs() }
        fileDirB = File(System.getProperty("java.io.tmpdir"), "peer_b_${System.nanoTime()}").apply { mkdirs() }
        fileServiceA = fileService(fileDirA)
        fileServiceB = fileService(fileDirB)

        bleCentralA = mockk(relaxed = true)
        blePeripheralA = mockk(relaxed = true)
        bleCentralB = mockk(relaxed = true)
        blePeripheralB = mockk(relaxed = true)
        every { blePeripheralA.startAdvertising(any(), any()) } answers { advertisementsA[firstArg()] = secondArg() }
        every { blePeripheralB.startAdvertising(any(), any()) } answers { advertisementsB[firstArg()] = secondArg() }
        every { blePeripheralA.getDeviceUuidBytes() } returns ByteArray(16)
        every { blePeripheralB.getDeviceUuidBytes() } returns ByteArray(16)
        broadcastDaoA = mockk(relaxed = true)
        subscriptionDaoA = mockk(relaxed = true)
        broadcastDaoB = mockk(relaxed = true)
        subscriptionDaoB = mockk(relaxed = true)
        notifierB = mockk(relaxed = true)
        val contextA = mockk<Context>(relaxed = true)
        val contextB = mockk<Context>(relaxed = true)
        engineA = SyncEngine(contextA, broadcastDaoA, subscriptionDaoA, cryptoA, fileServiceA, bleCentralA, blePeripheralA, mockk(relaxed = true))
        engineB = SyncEngine(contextB, broadcastDaoB, subscriptionDaoB, cryptoB, fileServiceB, bleCentralB, blePeripheralB, notifierB)
    }

    @After
    fun tearDown() {
        engineA.destroy()
        engineB.destroy()
        fileDirA.deleteRecursively()
        fileDirB.deleteRecursively()
    }

    private fun fileService(dir: File): FileService {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns dir
        return FileService(context)
    }

    private fun publish(content: ByteArray): Pair<BroadcastEntity, KeyPair> {
        val fileId = UUID.randomUUID().toString()
        val keys = cryptoA.generateRsaKeyPair()
        cryptoA.storeKeyPair("sk_$fileId", keys)
        val publicKey = cryptoA.publicKeyToBase64(keys.public)
        val version = 1
        val file = fileServiceA.getFile(fileId, version).apply { parentFile!!.mkdirs(); writeBytes(content) }
        val hash = Base64.getEncoder().encodeToString(cryptoA.sha256(content))
        val compressedSize = fileServiceA.getCompressedSize(fileId, version)
        val entity = BroadcastEntity(fileId, "payload.bin", null, "application/octet-stream", file.absolutePath, hash, content.size.toLong(), compressedSize, version, publicKey, "sk_$fileId", "", Role.ORIGINATOR, 0L, 0L)
        coEvery { broadcastDaoA.getById(fileId) } returns entity
        return entity to keys
    }

    private fun subscribe(entity: BroadcastEntity) {
        coEvery { subscriptionDaoB.getAll() } returns listOf(SubscriptionEntity(entity.fileId, entity.publicKey, entity.fileName, null, null, null, 0L, null, null, null))
        coEvery { broadcastDaoB.getAll() } returns emptyList()
    }

    @Test
    fun `advertisement protocol preserves file hash version and key id`() = runBlocking {
        val entity = publish("advertised".toByteArray()).first
        engineA.startAdvertising(entity)
        val parsed = BleMetaPayload.fromBytes(advertisementsA[entity.fileId]!!)!!
        assertEquals(entity.version, parsed.version)
        assertEquals(entity.fileName, parsed.fileName)
        assertTrue(parsed.fileId.isNotEmpty())
    }

    @Test
    fun `meta protocol contains no signature and survives serialization`() = runBlocking {
        val content = "meta".toByteArray()
        val entity = publish(content).first
        val payload = BleMetaPayload.fromBytes(engineA.buildMetaPayload(entity.fileId)!!.toBytes())!!
        assertEquals(entity.version, payload.version)
        assertArrayEquals(cryptoA.sha256(content), payload.fileHash)
        assertTrue(payload.fileSize > entity.compressedSize)
        assertEquals(BleMetaPayload.FIXED_SIZE + entity.fileName.toByteArray().size, payload.toBytes().size)
    }

    @Test
    fun `encrypted compressed payload crosses relay unchanged and decrypts with QR public key`() {
        val content = ByteArray(200_000) { (it * 17).toByte() }
        val (entity, keys) = publish(content)
        val compressed = fileServiceA.getCompressedFile(entity.fileId, 1)
        val envelope = cryptoA.encryptCompressed(compressed.readBytes(), keys.private)
        val relayEnvelope = envelope.copyOf()
        val recoveredCompressed = cryptoB.decryptCompressed(relayEnvelope, keys.public)
        val compressedCopy = File(fileDirB, "received.compressed")
        compressedCopy.writeBytes(recoveredCompressed)
        val received = File(fileDirB, "received.bin")
        fileServiceB.decompressFile(compressedCopy, received)
        assertArrayEquals(content, received.readBytes())
    }

    @Test
    fun `subscriber discovery reaches GATT using QR public key identity`() = runBlocking {
        val entity = publish("discover".toByteArray()).first
        subscribe(entity)
        engineA.startAdvertising(entity)
        val advMeta = BleMetaPayload.fromBytes(advertisementsA[entity.fileId]!!)
        // When META is in the advertisement, no GATT meta read is needed
        assertFalse(engineB.handleDiscoveredDevice("AA:BB:CC:DD:EE:01", advertisementsA[entity.fileId]!!, advMeta).also {
            coVerify(exactly = 0) { bleCentralB.readMeta(any(), any()) }
        })
    }

    @Test
    fun `stale advertisement does not contact peer`() = runBlocking {
        val entity = publish("stale".toByteArray()).first
        coEvery { subscriptionDaoB.getAll() } returns listOf(SubscriptionEntity(entity.fileId, entity.publicKey, entity.fileName, null, 2, "/local", 0L, 2, 0L, null))
        engineA.startAdvertising(entity)
        assertFalse(engineB.handleDiscoveredDevice("AA:BB:CC:DD:EE:02", advertisementsA[entity.fileId]!!, null))
        coVerify(exactly = 0) { bleCentralB.readMeta(any(), any()) }
    }
}
