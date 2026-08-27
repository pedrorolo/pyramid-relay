package p2p.broadcaster

import android.app.Application
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
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
class AdditionalServiceTest {

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
    fun `WifiDirectService intToBytes encodes correctly`() {
        val service = WifiDirectService(ApplicationProvider.getApplicationContext<Application>())
        val method = WifiDirectService::class.java.getDeclaredMethod("intToBytes", Int::class.javaPrimitiveType)
        method.isAccessible = true

        val result = method.invoke(service, 0x01020304) as ByteArray
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), result)

        val result2 = method.invoke(service, -1) as ByteArray
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), result2)

        val result3 = method.invoke(service, 0) as ByteArray
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), result3)
    }

    @Test
    fun `WifiDirectService longToBytes encodes correctly`() {
        val service = WifiDirectService(ApplicationProvider.getApplicationContext<Application>())
        val method = WifiDirectService::class.java.getDeclaredMethod("longToBytes", Long::class.javaPrimitiveType)
        method.isAccessible = true

        val result = method.invoke(service, 0x0102030405060708L) as ByteArray
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08), result)

        val result2 = method.invoke(service, -1L) as ByteArray
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1), result2)

        val result3 = method.invoke(service, 0L) as ByteArray
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0), result3)
    }

    @Test
    fun `WifiDirectService initialize calls WifiP2pManager`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = WifiDirectService(context)
        service.initialize()
    }

    @Test
    fun `WifiDirectService removeGroup calls manager`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = WifiDirectService(context)
        service.removeGroup()
    }

    @Test
    fun `WifiDirectService peer connect fails safely without initialization`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = WifiDirectService(context)
        val result = service.ensureConnectedToDevice(byteArrayOf(1, 2, 3, 4))
        assertNull(result)
    }

    @Test
    fun `NotificationService showUpdateNotification handles null intent`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = NotificationService(context)
        service.showUpdateNotification("test.txt", "file-1", 1, 2)
    }

    @Test
    fun `NotificationService constants are accessible`() {
        assertEquals("p2p_updates", NotificationService.CHANNEL_ID)
        assertEquals("File Updates", NotificationService.CHANNEL_NAME)
    }

    @Test
    fun `BlePeripheralService startGattServer handles missing bluetooth`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BlePeripheralService(context)
        service.startGattServer()
    }

    @Test
    fun `BleCentralService startScan handles missing scanner`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = BleCentralService(context)
        service.startScan()
    }

    @Test
    fun `BleForegroundService onBind returns null`() {
        val service = BleForegroundService()
        assertNull(service.onBind(null))
    }
}