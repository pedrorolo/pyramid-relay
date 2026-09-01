package com.pyramidrelay

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import java.io.File
import java.util.UUID
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FinalCoverageTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `P2PBroadcasterApp instantiation via Robolectric`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(app)
    }

    @Test
    fun `SyncEngine fetchAndUpdateBroadcast not called when meta null`() = runTest {
        val cryptoService = CryptoService()
        val broadcastDao = mockk<BroadcastDao>(relaxed = true)
        val subscriptionDao = mockk<SubscriptionDao>(relaxed = true)
        val fileService = mockk<FileService>(relaxed = true)
        val bleCentralService = mockk<BleCentralService>(relaxed = true)
        val blePeripheralService = mockk<BlePeripheralService>(relaxed = true)
        val notificationService = mockk<NotificationService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { broadcastDao.changeFlow } returns MutableStateFlow(0L)
        every { subscriptionDao.changeFlow } returns MutableStateFlow(0L)
        coEvery { bleCentralService.readMeta(any(), any()) } returns null

        val engine = SyncEngine(
            context,
            broadcastDao, subscriptionDao, cryptoService, fileService,
            bleCentralService, blePeripheralService, notificationService
        )

        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        cryptoService.storeKeyPair("sk", kp)
        cryptoService.storeKeyPair("sk", kp)
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val broadcast = BroadcastEntity(fileId, "test.txt", null, "text/plain", "/path", "hash", 1024, 1024, 1, pubKeyStr, "sk", "sig", Role.ORIGINATOR, 0, 0)
        coEvery { broadcastDao.getAll() } returns listOf(broadcast)

        val fileIdHash = cryptoService.fileIdHash(fileId)
        val keyId = cryptoService.keyId(pubKeyStr)
        val serviceData = ByteArray(14)
        fileIdHash.copyInto(serviceData, 0)
        serviceData[6] = 0; serviceData[7] = 0; serviceData[8] = 0; serviceData[9] = 2
        keyId.copyInto(serviceData, 10)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData)
        advanceUntilIdle()

        coVerify(exactly = 1) { bleCentralService.readMeta("AA:BB:CC:DD:EE:FF", any()) }
    }

    @Test
    fun `SyncEngine fetchAndUpdateSubscription not called when meta null`() = runTest {
        val cryptoService = CryptoService()
        val broadcastDao = mockk<BroadcastDao>(relaxed = true)
        val subscriptionDao = mockk<SubscriptionDao>(relaxed = true)
        val fileService = mockk<FileService>(relaxed = true)
        val bleCentralService = mockk<BleCentralService>(relaxed = true)
        val blePeripheralService = mockk<BlePeripheralService>(relaxed = true)
        val notificationService = mockk<NotificationService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { broadcastDao.changeFlow } returns MutableStateFlow(0L)
        every { subscriptionDao.changeFlow } returns MutableStateFlow(0L)
        coEvery { bleCentralService.readMeta(any(), any()) } returns null

        val engine = SyncEngine(
            context,
            broadcastDao, subscriptionDao, cryptoService, fileService,
            bleCentralService, blePeripheralService, notificationService
        )

        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val sub = SubscriptionEntity(fileId, pubKeyStr, "test.txt", null, null, null, 0, null, null, null)
        coEvery { subscriptionDao.getAll() } returns listOf(sub)
        coEvery { broadcastDao.getAll() } returns emptyList()

        val fileIdHash = cryptoService.fileIdHash(fileId)
        val keyId = cryptoService.keyId(pubKeyStr)
        val serviceData = ByteArray(14)
        fileIdHash.copyInto(serviceData, 0)
        serviceData[6] = 0; serviceData[7] = 0; serviceData[8] = 0; serviceData[9] = 2
        keyId.copyInto(serviceData, 10)

        engine.handleDiscoveredDevice("AA:BB:CC:DD:EE:FF", serviceData)
        advanceUntilIdle()

        coVerify(exactly = 1) { bleCentralService.readMeta("AA:BB:CC:DD:EE:FF", any()) }
    }

    @Test
    fun `SyncEngine buildMetaPayload with valid broadcast`() = runTest {
        val cryptoService = CryptoService()
        val broadcastDao = mockk<BroadcastDao>(relaxed = true)
        val subscriptionDao = mockk<SubscriptionDao>(relaxed = true)
        val fileService = mockk<FileService>(relaxed = true)
        val bleCentralService = mockk<BleCentralService>(relaxed = true)
        val blePeripheralService = mockk<BlePeripheralService>(relaxed = true)
        val notificationService = mockk<NotificationService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        val engine = SyncEngine(
            context,
            broadcastDao, subscriptionDao, cryptoService, fileService,
            bleCentralService, blePeripheralService, notificationService
        )

        val fileId = UUID.randomUUID().toString()
        val kp = cryptoService.generateRsaKeyPair()
        val pubKeyStr = cryptoService.publicKeyToBase64(kp.public)
        val hashBytes = cryptoService.sha256("test data".toByteArray())
        val hashStr = java.util.Base64.getEncoder().encodeToString(hashBytes)
        val compressed = File.createTempFile("payload", ".compressed")
        compressed.writeBytes("compressed".toByteArray())
        every { fileService.getVersionDir(fileId, 1) } returns compressed.parentFile
        every { fileService.getCompressedFile(fileId, 1) } returns compressed
        val broadcast = BroadcastEntity(fileId, "test.txt", null, "text/plain", "/path", hashStr, 2048, 2048, 1, pubKeyStr, "sk", "", Role.ORIGINATOR, 0, 0)
        coEvery { broadcastDao.getById(fileId) } returns broadcast

        val payload = engine.buildMetaPayload(fileId)!!
        assertEquals(16, payload.fileId.size)
        assertEquals(1, payload.version)
        assertTrue(payload.fileSize > 0L)
        assertArrayEquals(hashBytes, payload.fileHash)
        compressed.delete()
    }

    @Test
    fun `SubscriptionDao updateReceived`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase(context)
        val dao = SubscriptionDao(db)

        dao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, null, 0, null, null, null))
        dao.updateReceived("s1", 3, "/path", 3, 5000L)

        val result = dao.getById("s1")!!
        assertEquals(3, result.localVersion)
        assertEquals("/path", result.localUri)
        assertEquals(3, result.lastSeenVersion)
        assertEquals(5000L, result.lastSeenAt)
        db.close()
    }

    @Test
    fun `SubscriptionDao updateLastSeen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase(context)
        val dao = SubscriptionDao(db)

        dao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, null, 0, null, null, null))
        dao.updateLastSeen("s1", 7, 8000L)

        val result = dao.getById("s1")!!
        assertEquals(7, result.lastSeenVersion)
        assertEquals(8000L, result.lastSeenAt)
        db.close()
    }

    @Test
    fun `SubscriptionDao updateLastNotified`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase(context)
        val dao = SubscriptionDao(db)

        dao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, null, 0, null, null, null))
        dao.updateLastNotified("s1", 9)

        val result = dao.getById("s1")!!
        assertEquals(9, result.lastNotifiedVersion)
        db.close()
    }

    @Test
    fun `SubscriptionDao handles null fields`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase(context)
        val dao = SubscriptionDao(db)

        dao.upsert(SubscriptionEntity("s1", "pk", null, null, null, null, 100, null, null, null))
        val result = dao.getById("s1")!!
        assertNull(result.fileName)
        assertNull(result.localVersion)
        assertNull(result.localUri)
        assertNull(result.lastSeenVersion)
        assertNull(result.lastSeenAt)
        assertNull(result.lastNotifiedVersion)
        db.close()
    }

    @Test
    fun `BroadcastDao updateVersion`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase(context)
        val dao = BroadcastDao(db)

        dao.upsert(BroadcastEntity("f1", "a", null, "t", "/p", "old", 100, 100, 1, "pk", null, "s", Role.ORIGINATOR, 0, 0))
        dao.updateVersion("f1", 2, "new_hash", "new_sig", "/new", 200L, 200L, 999L)

        val result = dao.getById("f1")!!
        assertEquals(2, result.version)
        assertEquals("new_hash", result.fileHash)
        assertEquals("new_sig", result.signature)
        assertEquals("/new", result.internalUri)
        assertEquals(200L, result.fileSize)
        assertEquals(999L, result.updatedAt)
        db.close()
    }
}
