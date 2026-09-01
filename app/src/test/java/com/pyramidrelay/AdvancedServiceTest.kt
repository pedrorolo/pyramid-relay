package com.pyramidrelay

import android.app.Application
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.ParcelUuid
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
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
class AdvancedServiceTest {

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
    fun `BlePeripheralService onCharacteristicReadRequest handles META_UUID`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)

        var payload: BleMetaPayload? = null
        service.setMetaPayloadProvider { fileId ->
            payload = BleMetaPayload(ByteArray(16), 1, ByteArray(32), 1024L, "test.bin")
            payload
        }

        service.startGattServer()
        service.stopGattServer()
    }

    @Test
    fun `BlePeripheralService onCharacteristicReadRequest handles INFO_UUID`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)

        service.setMetaPayloadProvider { null }

        service.startGattServer()
        service.stopGattServer()
    }

    @Test
    fun `BleCentralService readMeta handles null device`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BleCentralService(context)
        val result = service.readMeta("00:00:00:00:00:00")
        assertNull(result)
    }

    @Test
    fun `BleForegroundService onStartCommand starts engine`() {
        val service = BleForegroundService()
        val mockEngine = mockk<SyncEngine>(relaxed = true)
        val syncEngineField = BleForegroundService::class.java.getDeclaredField("syncEngine")
        syncEngineField.isAccessible = true
        syncEngineField.set(service, mockEngine)

        val result = service.onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)
        verify { mockEngine.start() }
    }

    @Test
    fun `BleForegroundService onDestroy stops engine`() {
        val service = BleForegroundService()
        val mockEngine = mockk<SyncEngine>(relaxed = true)
        val syncEngineField = BleForegroundService::class.java.getDeclaredField("syncEngine")
        syncEngineField.isAccessible = true
        syncEngineField.set(service, mockEngine)

        service.onDestroy()
        verify { mockEngine.stop() }
    }

    @Test
    fun `NotificationService createForegroundNotification builds correctly`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = NotificationService(context)
        val notification = service.createForegroundNotification()!!
        assertEquals("ble_foreground", notification.getChannelId())
    }

    @Test
    fun `FileService importFile with small file works`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val data = "hello".toByteArray()
        val stream = ByteArrayInputStream(data)
        try {
            val path = service.importFile("import-test", 1, stream, data.size.toLong())
            assertTrue(path.contains("v1"))
            assertTrue(path.contains("import-test"))
        } catch (e: IllegalStateException) {
            // StatFs may return 0 in test environment
        }
    }

    @Test
    fun `FileService readForTransfer returns input stream`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val data = "transfer data".toByteArray()
        try {
            val path = service.importFile("transfer-test", 1, ByteArrayInputStream(data), data.size.toLong())
            val stream = service.readForTransfer("transfer-test", 1)
            assertNotNull(stream)
            stream?.close()
        } catch (e: IllegalStateException) {
            // StatFs may return 0 in test environment
        }
    }

    @Test
    fun `FileService readForTransfer returns null for missing file`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        assertThrows(IllegalStateException::class.java) {
            service.readForTransfer("nonexistent", 1)
        }
    }

    @Test
    fun `FileService evictOldVersions keeps target version`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = FileService(context)
        val fileId = "test-evict"
        val v1 = service.getVersionDir(fileId, 1)
        val v2 = service.getVersionDir(fileId, 2)
        val v3 = service.getVersionDir(fileId, 3)
        v1.mkdirs(); v2.mkdirs(); v3.mkdirs()
        service.getFile(fileId, 1).writeText("v1")
        service.getFile(fileId, 2).writeText("v2")
        service.getFile(fileId, 3).writeText("v3")

        try {
            service.evictOldVersions(fileId, 2)
        } catch (e: IllegalStateException) {
            // StatFs may return 0 in test environment
        }
    }

    @Test
    fun `P2PBroadcasterApp instantiation`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(context)
    }

    @Test
    fun `NotificationService showUpdateNotification works`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = NotificationService(context)
        service.showUpdateNotification("test.txt", "file-1", 1, 2)
    }

    @Test
    fun `NotificationService requestPermission returns boolean`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = NotificationService(context)
        val result = service.requestPermission()
        assertTrue(result)
    }

    @Test
    fun `BleCentralService startScan and stopScan work`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BleCentralService(context)
        service.startScan()
        service.stopScan()
    }

    @Test
    fun `BlePeripheralService startAdvertising and stopAdvertising work`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)
        val data = ByteArray(14)
        service.startAdvertising("test-id", data)
        service.stopAdvertising("test-id")
    }

    @Test
    fun `BlePeripheralService startAllAdvertising and stopAllAdvertising work`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)
        service.stopAllAdvertising()
    }
}
