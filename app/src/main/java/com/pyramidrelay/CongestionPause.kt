package com.pyramidrelay

import com.pyramidrelay.BlePeripheralService
import java.util.concurrent.ConcurrentHashMap

class CongestionPause(val contentKey: String, val basePauseMs: Long) {
    var lastCongestionTime: Long = 0L
    var currentBackoff: Long = basePauseMs
    var expiry: Long = 0L

    fun isExpired(): Boolean = System.currentTimeMillis() > expiry

    fun remainingMs(): Long = (expiry - System.currentTimeMillis()).coerceAtLeast(0)

    fun record(): Long {
        val now = System.currentTimeMillis()
        val isFirstRecord = lastCongestionTime == 0L
        val isExpired = !isFirstRecord && (now - lastCongestionTime) >= CongestionPauses.MAX_PAUSE_MS
        lastCongestionTime = now
        if (isFirstRecord || isExpired) {
            currentBackoff = basePauseMs
        } else {
            currentBackoff = (currentBackoff * CongestionPauses.MULTIPLIER)
                .toLong().coerceAtMost(CongestionPauses.MAX_PAUSE_MS)
        }
        val jitter = ((Math.random() * 0.5 - 0.25) * currentBackoff).toLong()
        val pauseWithJitter = (currentBackoff + jitter).coerceAtLeast(1_000L)
        expiry = now + pauseWithJitter
        return pauseWithJitter
    }

    fun reset() {
        lastCongestionTime = 0L
        currentBackoff = basePauseMs
        expiry = 0L
    }
}

object CongestionPauses {
    const val MAX_PAUSE_MS = 300_000L
    const val MULTIPLIER = 1.5
    const val ROTATION_INTERVAL_MS = BlePeripheralService.ROTATION_INTERVAL_MS

    private val pauses = ConcurrentHashMap<String, CongestionPause>()

    fun isCongested(contentKey: String): Boolean {
        val pause = pauses[contentKey] ?: return false
        if (pause.isExpired()) {
            return false
        }
        return true
    }

    fun remainingMs(contentKey: String): Long {
        val pause = pauses[contentKey] ?: return 0
        if (pause.isExpired()) {
            pauses.remove(contentKey)
            return 0
        }
        return pause.remainingMs()
    }

    fun record(contentKey: String, basePauseMs: Long): Long {
        val pause = pauses.getOrPut(contentKey) { CongestionPause(contentKey, basePauseMs) }
        val wasReset = if (pause.lastCongestionTime > 0 && (System.currentTimeMillis() - pause.lastCongestionTime) >= MAX_PAUSE_MS) {
            pause.reset()
            true
        } else false
        val pauseMs = pause.record()
        val pauseSec = pauseMs / 1000
        val backoffSec = pause.currentBackoff / 1000
        if (wasReset) {
            EventLog.log("sync", "Congestion recorded for ${contentKey.take(12)}: TTL=${pauseSec}s (reset to base=${backoffSec}s)")
        } else {
            EventLog.log("sync", "Congestion recorded for ${contentKey.take(12)}: TTL=${pauseSec}s (backoff=${backoffSec}s)")
        }
        return pause.remainingMs()
    }

    fun reset(contentKey: String) {
        if (pauses.remove(contentKey) != null) {
            EventLog.log("sync", "Congestion pause cleared for ${contentKey.take(12)} (transfer succeeded)")
        }
    }

    fun cleanup() {
        val before = pauses.size
        pauses.entries.removeIf { it.value.isExpired() }
        val removed = before - pauses.size
        if (removed > 0) EventLog.log("sync", "Cleaned up $removed expired congestion pauses")
    }

    fun clearAll() {
        pauses.clear()
    }
}
