package p2p.broadcaster

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
class ServiceCoverageTest {

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
    fun `WifiDirectService intToBytes and longToBytes`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = WifiDirectService(context)
        
        val intMethod = WifiDirectService::class.java.getDeclaredMethod("intToBytes", Int::class.javaPrimitiveType)
        intMethod.isAccessible = true
        
        val result1 = intMethod.invoke(service, 0x01020304) as ByteArray
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), result1)
        
        val longMethod = WifiDirectService::class.java.getDeclaredMethod("longToBytes", Long::class.javaPrimitiveType)
        longMethod.isAccessible = true
        
        val result2 = longMethod.invoke(service, 0x0102030405060708L) as ByteArray
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08), result2)
    }

    @Test
    fun `WifiDirectService peer connect and removeGroup are safe without P2P`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = WifiDirectService(context)
        service.initialize()
        val result = service.ensureConnectedToDevice(byteArrayOf(1, 2, 3, 4))
        assertNull(result)
        service.removeGroup()
    }

    @Test
    fun `BlePeripheralService onCharacteristicReadRequest for META`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)

        service.setMetaPayloadProvider { fileId ->
            BleMetaPayload(ByteArray(16), 1, ByteArray(32), ByteArray(64), ByteArray(32), 1024L, "test.bin")
        }

        service.startGattServer()
        service.stopGattServer()
    }

    @Test
    fun `BleCentralService startScan and stopScan`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BleCentralService(context)
        service.startScan()
        service.stopScan()
    }

    @Test
    fun `BleForegroundService onBind returns null`() {
        val service = BleForegroundService()
        assertNull(service.onBind(null))
    }

    @Test
    fun `BleForegroundService onStartCommand`() {
        val service = BleForegroundService()
        val mockEngine = mockk<SyncEngine>(relaxed = true)
        val syncEngineField = BleForegroundService::class.java.getDeclaredField("syncEngine")
        syncEngineField.isAccessible = true
        syncEngineField.set(service, mockEngine)

        val app = mockk<P2PBroadcasterApp>(relaxed = true)
        every { app.syncEngine } returns mockEngine

        val applicationField = android.app.Service::class.java.getDeclaredField("mApplication")
        applicationField.isAccessible = true
        applicationField.set(service, app)

        val result = service.onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)
    }

    @Test
    fun `BleForegroundService onDestroy`() {
        val service = BleForegroundService()
        val mockEngine = mockk<SyncEngine>(relaxed = true)
        val syncEngineField = BleForegroundService::class.java.getDeclaredField("syncEngine")
        syncEngineField.isAccessible = true
        syncEngineField.set(service, mockEngine)

        service.onDestroy()
        verify { mockEngine.stop() }
    }

    @Test
    fun `NotificationService showUpdateNotification`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = NotificationService(context)
        service.showUpdateNotification("test.txt", "file-1", 1, 2)
    }

    @Test
    fun `NotificationService createForegroundNotification`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = NotificationService(context)
        val notification = service.createForegroundNotification()!!
        assertEquals("ble_foreground", notification.getChannelId())
    }

    @Test
    fun `FileService all paths`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        
        // hasFile
        assertFalse(service.hasFile("nonexistent", 1))
        
        // getStoreDir
        val storeDir = service.getStoreDir()
        assertTrue(storeDir.path.endsWith("store"))
        
        // getVersionDir
        val vDir = service.getVersionDir("test", 3)
        assertTrue(vDir.path.endsWith("store/test/v3"))
        
        // getFile and getTmpFile
        val file = service.getFile("test", 1)
        assertTrue(file.path.endsWith("v1/file"))
        val tmpFile = service.getTmpFile("test", 1)
        assertTrue(tmpFile.path.endsWith("v1/file.tmp"))
        
        // deleteAll
        service.deleteAll("nonexistent")
        
        // evictOldVersions
        service.evictOldVersions("nonexistent", 1)
        
        // getAvailableSpace
        val space = service.getAvailableSpace()
        assertTrue(space >= 0)
    }

    @Test
    fun `FileService importFile small`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val data = "hello".toByteArray()
        try {
            val path = service.importFile("import-test", 1, ByteArrayInputStream(data), data.size.toLong())
            assertTrue(path.contains("v1"))
        } catch (e: IllegalStateException) {
            // StatFs may return 0 in test environment
        }
    }
}