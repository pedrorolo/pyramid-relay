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