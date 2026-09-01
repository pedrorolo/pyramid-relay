package com.pyramidrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.pyramidrelay.ui.theme.P2PBroadcasterTheme
import com.pyramidrelay.ui.theme.Purple80
import com.pyramidrelay.ui.theme.Purple40
import com.pyramidrelay.ui.theme.PurpleGrey80
import com.pyramidrelay.ui.theme.PurpleGrey40
import com.pyramidrelay.ui.theme.Pink80
import com.pyramidrelay.ui.theme.Pink40

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComposeScreenTest {

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
    fun `Color values are defined`() {
        assert(Purple80 != Purple40)
        assert(PurpleGrey80 != PurpleGrey40)
        assert(Pink80 != Pink40)
    }

    @Test
    fun `formatSize handles bytes`() {
        val fn = getFormatSize()
        assertEquals("500 B", fn(500))
    }

    @Test
    fun `formatSize handles KB`() {
        val fn = getFormatSize()
        assertEquals("1 KB", fn(1024))
    }

    @Test
    fun `formatSize handles MB`() {
        val fn = getFormatSize()
        assertEquals("5 MB", fn(5L * 1024 * 1024))
    }

    @Test
    fun `formatSize handles GB`() {
        val fn = getFormatSize()
        assertEquals("2 GB", fn(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatSize boundary at 1024 bytes is KB`() {
        val fn = getFormatSize()
        assertEquals("1 KB", fn(1024))
    }

    @Test
    fun `formatSize boundary at 1MB is MB`() {
        val fn = getFormatSize()
        assertEquals("1 MB", fn(1024L * 1024))
    }

    @Test
    fun `formatSize boundary at 1GB is GB`() {
        val fn = getFormatSize()
        assertEquals("1 GB", fn(1024L * 1024 * 1024))
    }

    @Test
    fun `formatSize zero bytes`() {
        val fn = getFormatSize()
        assertEquals("0 B", fn(0))
    }

    @Test
    fun `formatSize 1023 bytes`() {
        val fn = getFormatSize()
        assertEquals("1023 B", fn(1023))
    }

    @Test
    fun `formatSize 1025 bytes`() {
        val fn = getFormatSize()
        assertEquals("1 KB", fn(1025))
    }

    private fun getFormatSize(): (Long) -> String {
        return { bytes ->
            if (bytes < 1024) "$bytes B"
            else if (bytes < 1024 * 1024) "${bytes / 1024} KB"
            else if (bytes < 1024 * 1024 * 1024) "${bytes / (1024 * 1024)} MB"
            else "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}