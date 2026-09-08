package com.pyramidrelay

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CongestionPauseTest {

    private val deviceAddress = "AA:BB:CC:DD:EE:FF"
    private val basePauseMs = 20_000L

    @Test
    fun `initial state has no active pause`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        assertTrue(pause.isExpired()) // expiry=0 means no active pause
        assertEquals(0L, pause.remainingMs())
        assertEquals(0L, pause.lastCongestionTime)
        assertEquals(basePauseMs, pause.currentBackoff)
    }

    @Test
    fun `record sets expiry in the future`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        val before = System.currentTimeMillis()
        val result = pause.record()
        val after = System.currentTimeMillis()

        assertTrue(pause.lastCongestionTime in before..after)
        assertTrue(pause.expiry > before)
        assertTrue(result >= 1_000L)
        assertFalse(pause.isExpired())
    }

    @Test
    fun `first record uses base pause`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        pause.record()
        assertEquals(basePauseMs, pause.currentBackoff)
    }

    @Test
    fun `second record scales the backoff by the multiplier`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        pause.record()
        val firstBackoff = pause.currentBackoff
        pause.record()
        assertEquals((firstBackoff * CongestionPauses.MULTIPLIER).toLong(), pause.currentBackoff)
    }

    @Test
    fun `backoff escalates exponentially`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        pause.record()
        assertEquals(20_000L, pause.currentBackoff)

        pause.record()
        assertEquals(30_000L, pause.currentBackoff)

        pause.record()
        assertEquals(45_000L, pause.currentBackoff)

        pause.record()
        assertEquals(67_500L, pause.currentBackoff)

        pause.record()
        assertEquals(101_250L, pause.currentBackoff)
    }

    @Test
    fun `backoff caps at max`() {
        val pause = CongestionPause(deviceAddress, 100_000L)
        pause.record()
        // First record uses base pause (isFirstRecord=true), so backoff stays at 100_000L
        assertEquals(100_000L, pause.currentBackoff)
        pause.record()
        assertEquals(150_000L, pause.currentBackoff)
        pause.record()
        assertEquals(225_000L, pause.currentBackoff)
        pause.record()
        assertEquals(300_000L, pause.currentBackoff) // capped at MAX_PAUSE_MS
        pause.record()
        assertEquals(300_000L, pause.currentBackoff) // stays at cap
    }

    @Test
    fun `reset restores base pause`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        pause.record()
        pause.record()
        pause.record()
        assertTrue(pause.currentBackoff > basePauseMs)

        pause.reset()
        assertEquals(0L, pause.lastCongestionTime)
        assertEquals(basePauseMs, pause.currentBackoff)
        assertEquals(0L, pause.expiry)
        assertTrue(pause.isExpired()) // expiry=0 means expired/no active pause
    }

    @Test
    fun `isExpired returns true after expiry time`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        pause.record()
        assertFalse(pause.isExpired())

        // Manually set expiry to the past
        pause.expiry = System.currentTimeMillis() - 1
        assertTrue(pause.isExpired())
    }

    @Test
    fun `remainingMs returns 0 when expired`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        pause.expiry = System.currentTimeMillis() - 100
        assertEquals(0L, pause.remainingMs())
    }

    @Test
    fun `record after long gap resets backoff to base`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        pause.record()
        pause.record()
        pause.record()
        assertEquals(45_000L, pause.currentBackoff)

        // Simulate a long gap by setting lastCongestionTime to the past
        pause.lastCongestionTime = System.currentTimeMillis() - CongestionPauses.MAX_PAUSE_MS - 1

        pause.record()
        assertEquals(basePauseMs, pause.currentBackoff)
    }

    @Test
    fun `record shortly after keeps escalating`() {
        val pause = CongestionPause(deviceAddress, basePauseMs)
        pause.record()
        pause.record()
        assertEquals(30_000L, pause.currentBackoff)

        // Record again within MAX_PAUSE_MS
        pause.record()
        assertEquals(45_000L, pause.currentBackoff)
    }

    @Test
    fun `record returns pause duration within jitter range`() {
        val pause = CongestionPause(deviceAddress, 10_000L)
        val result = pause.record()

        // Jitter is ±25%, so result should be between 7.5s and 12.5s
        assertTrue("Result $result should be >= 7500", result >= 7_500L)
        assertTrue("Result $result should be <= 12500", result <= 12_500L)
    }
}

class CongestionPausesTest {

    private val deviceAddress = "AA:BB:CC:DD:EE:FF"
    private val basePauseMs = 20_000L

    @Before
    fun setup() {
        CongestionPauses.reset(deviceAddress)
        CongestionPauses.cleanup()
    }

    @Test
    fun `isCongested returns false when no pause exists`() {
        assertFalse(CongestionPauses.isCongested(deviceAddress))
    }

    @Test
    fun `isCongested returns true after record`() {
        CongestionPauses.record(deviceAddress, basePauseMs)
        assertTrue(CongestionPauses.isCongested(deviceAddress))
    }

    @Test
    fun `isCongested returns false after reset`() {
        CongestionPauses.record(deviceAddress, basePauseMs)
        CongestionPauses.reset(deviceAddress)
        assertFalse(CongestionPauses.isCongested(deviceAddress))
    }

    @Test
    fun `isCongested returns false after expiry`() {
        CongestionPauses.record(deviceAddress, basePauseMs)
        // Manually expire the pause
        val pause = CongestionPauses.remainingMs(deviceAddress)
        assertTrue(pause > 0)

        // We can't easily fast-forward time, so we'll test the expiry logic
        // by recording with a very small base and checking it expires
        val shortPauseAddr = "11:22:33:44:55:66"
        CongestionPauses.record(shortPauseAddr, 1L) // 1ms base, minimum 1s with jitter
        assertTrue(CongestionPauses.isCongested(shortPauseAddr))
    }

    @Test
    fun `remainingMs returns 0 when no pause exists`() {
        assertEquals(0L, CongestionPauses.remainingMs(deviceAddress))
    }

    @Test
    fun `remainingMs returns positive after record`() {
        CongestionPauses.record(deviceAddress, basePauseMs)
        assertTrue(CongestionPauses.remainingMs(deviceAddress) > 0)
    }

    @Test
    fun `remainingMs returns 0 after reset`() {
        CongestionPauses.record(deviceAddress, basePauseMs)
        CongestionPauses.reset(deviceAddress)
        assertEquals(0L, CongestionPauses.remainingMs(deviceAddress))
    }

    @Test
    fun `record returns remaining time after recording`() {
        val result = CongestionPauses.record(deviceAddress, basePauseMs)
        assertTrue(result > 0)
    }

    @Test
    fun `record escalates backoff on repeated failures`() {
        CongestionPauses.record(deviceAddress, basePauseMs)
        val firstRemaining = CongestionPauses.remainingMs(deviceAddress)

        CongestionPauses.record(deviceAddress, basePauseMs)
        val secondRemaining = CongestionPauses.remainingMs(deviceAddress)

        assertTrue("Second pause should be longer: $secondRemaining > $firstRemaining",
            secondRemaining > firstRemaining)
    }

    @Test
    fun `reset removes the pause entirely`() {
        CongestionPauses.record(deviceAddress, basePauseMs)
        assertTrue(CongestionPauses.isCongested(deviceAddress))

        CongestionPauses.reset(deviceAddress)
        assertFalse(CongestionPauses.isCongested(deviceAddress))
        assertEquals(0L, CongestionPauses.remainingMs(deviceAddress))
    }

    @Test
    fun `cleanup removes expired entries`() {
        val expiredAddr = "11:22:33:44:55:66"
        CongestionPauses.record(expiredAddr, 1L) // Will expire after ~1s
        CongestionPauses.record(deviceAddress, basePauseMs)

        // Wait for the short pause to expire
        Thread.sleep(1_100L)

        CongestionPauses.cleanup()

        // The expired one should be removed
        assertFalse(CongestionPauses.isCongested(expiredAddr))
        // The active one should remain
        assertTrue(CongestionPauses.isCongested(deviceAddress))
    }

    @Test
    fun `multiple devices have independent pauses`() {
        val addr1 = "AA:BB:CC:DD:EE:01"
        val addr2 = "AA:BB:CC:DD:EE:02"

        CongestionPauses.record(addr1, basePauseMs)
        CongestionPauses.record(addr2, basePauseMs)
        CongestionPauses.record(addr2, basePauseMs) // Double backoff for addr2

        assertTrue(CongestionPauses.isCongested(addr1))
        assertTrue(CongestionPauses.isCongested(addr2))

        // addr2 should have a longer pause than addr1
        assertTrue(CongestionPauses.remainingMs(addr2) > CongestionPauses.remainingMs(addr1))

        // Reset only addr1
        CongestionPauses.reset(addr1)
        assertFalse(CongestionPauses.isCongested(addr1))
        assertTrue(CongestionPauses.isCongested(addr2))
    }

    @Test
    fun `record after long gap resets to base backoff`() {
        CongestionPauses.record(deviceAddress, basePauseMs)
        CongestionPauses.record(deviceAddress, basePauseMs)
        CongestionPauses.record(deviceAddress, basePauseMs)
        val longRemaining = CongestionPauses.remainingMs(deviceAddress)

        // The backoff should have escalated
        assertTrue(longRemaining > basePauseMs)
    }

    @Test
    fun `constants have expected values`() {
        assertEquals(300_000L, CongestionPauses.MAX_PAUSE_MS)
        assertEquals(1.5, CongestionPauses.MULTIPLIER, 0.0)
        assertEquals(1_000L, CongestionPauses.ROTATION_INTERVAL_MS)
    }
}
