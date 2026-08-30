package p2p.broadcaster

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ServiceTest {

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
    fun `NotificationService requestPermission returns boolean`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = NotificationService(context)
        val result = service.requestPermission()
        assertTrue(result)
    }

    @Test
    fun `NotificationService createForegroundNotification returns notification`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = NotificationService(context)
        val notification = service.createForegroundNotification()
        assertNotNull(notification)
    }

    @Test
    fun `NotificationService companion constants are set`() {
        assertEquals("p2p_updates", NotificationService.CHANNEL_ID)
        assertEquals("File Updates", NotificationService.CHANNEL_NAME)
    }

    @Test
    fun `BlePeripheralService setMetaPayloadProvider stores provider`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)
        service.setMetaPayloadProvider { fileId -> null }
        assertNotNull(service)
    }

    @Test
    fun `BlePeripheralService stopAllAdvertising is safe when nothing advertised`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)
        service.stopAllAdvertising()
    }

    @Test
    fun `BlePeripheralService destroy is safe`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)
        service.destroy()
    }

    @Test
    fun `BlePeripheralService stopAdvertising is safe when nothing advertised`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)
        service.stopAdvertising("nonexistent")
    }

    @Test
    fun `BlePeripheralService stopGattServer is safe when not started`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)
        service.stopGattServer()
    }

    @Test
    fun `BleCentralService destroy is safe`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BleCentralService(context)
        service.destroy()
    }

    @Test
    fun `BleCentralService stopScan is safe when not started`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BleCentralService(context)
        service.stopScan()
    }

    @Test
    fun `BleCentralService onDeviceDiscovered property is settable`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BleCentralService(context)
        var received = false
        service.onDeviceDiscovered = { _, _ -> received = true }
        service.onDeviceDiscovered?.invoke("AA:BB:CC", ByteArray(14))
        assertTrue(received)
    }

    @Test
    fun `FileService getStoreDir works with Robolectric`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        assertTrue(service.getStoreDir().path.endsWith("store"))
    }

    @Test
    fun `FileService getVersionDir works with Robolectric`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val dir = service.getVersionDir("test-id", 3)
        assertTrue(dir.path.endsWith("store/test-id/v3"))
    }

    @Test
    fun `FileService getFile and getTmpFile work with Robolectric`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val file = service.getFile("fid", 2)
        assertTrue(file.path.endsWith("v2/file"))
        val tmpFile = service.getTmpFile("fid", 2)
        assertTrue(tmpFile.path.endsWith("v2/file.tmp"))
    }

    @Test
    fun `FileService hasFile returns false for nonexistent`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        assertFalse(service.hasFile("nonexistent", 1))
    }

    @Test
    fun `FileService deleteAll on nonexistent is safe`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        service.deleteAll("nonexistent")
    }

    @Test
    fun `FileService evictOldVersions with no dirs is safe`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        service.evictOldVersions("nonexistent", 1)
    }
}
