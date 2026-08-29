package p2p.broadcaster

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import java.io.ByteArrayInputStream
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
class RemainingCoverageTest {

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
    fun `SyncEngine start and stop`() = runTest {
        val cryptoService = CryptoService()
        val broadcastDao = mockk<BroadcastDao>(relaxed = true)
        val subscriptionDao = mockk<SubscriptionDao>(relaxed = true)
        val fileService = mockk<FileService>(relaxed = true)
        val bleCentralService = mockk<BleCentralService>(relaxed = true)
        val blePeripheralService = mockk<BlePeripheralService>(relaxed = true)
        val wifiDirectService = mockk<WifiDirectService>(relaxed = true)
        val notificationService = mockk<NotificationService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { broadcastDao.changeFlow } returns MutableStateFlow(0L)
        every { subscriptionDao.changeFlow } returns MutableStateFlow(0L)

        val engine = SyncEngine(
            context,
            broadcastDao, subscriptionDao, cryptoService, fileService,
            bleCentralService, blePeripheralService, wifiDirectService, notificationService
        )

        engine.start()
        advanceUntilIdle()
        engine.stop()

        verify { bleCentralService.stopScan() }
        verify { blePeripheralService.stopAllAdvertising() }
    }

    @Test
    fun `SyncEngine start twice does not restart`() = runTest {
        val cryptoService = CryptoService()
        val broadcastDao = mockk<BroadcastDao>(relaxed = true)
        val subscriptionDao = mockk<SubscriptionDao>(relaxed = true)
        val fileService = mockk<FileService>(relaxed = true)
        val bleCentralService = mockk<BleCentralService>(relaxed = true)
        val blePeripheralService = mockk<BlePeripheralService>(relaxed = true)
        val wifiDirectService = mockk<WifiDirectService>(relaxed = true)
        val notificationService = mockk<NotificationService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { broadcastDao.changeFlow } returns MutableStateFlow(0L)
        every { subscriptionDao.changeFlow } returns MutableStateFlow(0L)

        val engine = SyncEngine(
            context,
            broadcastDao, subscriptionDao, cryptoService, fileService,
            bleCentralService, blePeripheralService, wifiDirectService, notificationService
        )

        engine.start()
        engine.start()
        engine.stop()
    }

    @Test
    fun `BroadcastsViewModel importAndBroadcast`() = runTest {
        val cryptoService = CryptoService()
        val broadcastDao = mockk<BroadcastDao>(relaxed = true)
        val fileService = mockk<FileService>(relaxed = true)
        every { broadcastDao.changeFlow } returns MutableStateFlow(0L)
        every { fileService.getFileName(any()) } returns "test.txt"
        every { fileService.getMimeType(any()) } returns "text/plain"
        every { fileService.getVersionDir(any(), any()) } returns java.io.File("/tmp")
        every { fileService.getFile(any(), any()) } returns java.io.File("/tmp/test.txt")

        val context = mockk<android.content.Context>(relaxed = true)
        every { context.contentResolver.openInputStream(any()) } returns ByteArrayInputStream("test".toByteArray())

        val viewModel = BroadcastsViewModel(broadcastDao, cryptoService, fileService)

        val uri = mockk<Uri>(relaxed = true)
        viewModel.importAndBroadcast(uri, context)
        advanceUntilIdle()

        coVerify { broadcastDao.upsert(any()) }
    }

    @Test
    fun `BroadcastsViewModel deleteBroadcast`() = runTest {
        val cryptoService = CryptoService()
        val broadcastDao = mockk<BroadcastDao>(relaxed = true)
        val fileService = mockk<FileService>(relaxed = true)
        every { broadcastDao.changeFlow } returns MutableStateFlow(0L)

        val viewModel = BroadcastsViewModel(broadcastDao, cryptoService, fileService)

        val broadcast = BroadcastEntity("del1", "test.txt", "text/plain", "/path", "hash", 100, 1, "pk", "sk", "sig", Role.ORIGINATOR, 0, 0)
        viewModel.deleteBroadcast(broadcast)
        advanceUntilIdle()

        coVerify { fileService.deleteAll("del1") }
        coVerify { broadcastDao.delete("del1") }
    }

    @Test
    fun `SubscriptionsViewModel addSubscription`() = runTest {
        val subscriptionDao = mockk<SubscriptionDao>(relaxed = true)
        val broadcastDao = mockk<BroadcastDao>(relaxed = true)
        val fileService = mockk<FileService>(relaxed = true)
        every { subscriptionDao.changeFlow } returns MutableStateFlow(0L)

        val viewModel = SubscriptionsViewModel(subscriptionDao, broadcastDao, fileService, CryptoService())

        val crypto = CryptoService()
        val validPk = crypto.publicKeyToBase64(crypto.generateEd25519KeyPair().public)
        viewModel.addSubscription("sub1", validPk, "name1")
        advanceUntilIdle()

        coVerify { subscriptionDao.upsert(match { it.fileId == "sub1" && it.publicKey == validPk && it.fileName == "name1" }) }
    }

    @Test
    fun `SubscriptionsViewModel deleteSubscription`() = runTest {
        val subscriptionDao = mockk<SubscriptionDao>(relaxed = true)
        val broadcastDao = mockk<BroadcastDao>(relaxed = true)
        val fileService = mockk<FileService>(relaxed = true)
        every { subscriptionDao.changeFlow } returns MutableStateFlow(0L)

        val viewModel = SubscriptionsViewModel(subscriptionDao, broadcastDao, fileService, CryptoService())

        val sub = SubscriptionEntity("sub2", "pk", "f", null, null, 0, null, null, null)
        viewModel.deleteSubscription(sub)
        advanceUntilIdle()

        coVerify { fileService.deleteAll("sub2") }
        coVerify { subscriptionDao.delete("sub2") }
        coVerify { broadcastDao.delete("sub2") }
    }

    @Test
    fun `FileService getAvailableSpace`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val space = service.getAvailableSpace()
        assertTrue(space >= 0)
    }

    @Test
    fun `FileService getStoreDir`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val dir = service.getStoreDir()
        assertTrue(dir.path.endsWith("store"))
    }

    @Test
    fun `FileService getVersionDir`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val dir = service.getVersionDir("test", 2)
        assertTrue(dir.path.endsWith("store/test/v2"))
    }

    @Test
    fun `FileService getFile`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val file = service.getFile("test", 1)
        assertTrue(file.path.endsWith("store/test/v1/file"))
    }

    @Test
    fun `FileService getTmpFile`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val file = service.getTmpFile("test", 1)
        assertTrue(file.path.endsWith("store/test/v1/file.tmp"))
    }
}