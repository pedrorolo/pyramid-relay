package com.pyramidrelay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight in-app activity log for debugging peer communication.
 * Non-verbose: one line per communication / transfer / processing event.
 * Every entry is also mirrored to Android logcat under tag "EventLog"
 * so sessions can be analyzed with `adb logcat -s EventLog`.
 */
object EventLog {

    data class Entry(val timestamp: Long, val tag: String, val message: String) {
        fun formatted(): String = "${format.format(Date(timestamp))} [$tag] $message"
    }

    private const val MAX_ENTRIES = 1000
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val LOGCAT_TAG = "EventLog"

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Records one event; oldest entries are dropped when [MAX_ENTRIES] is exceeded. */
    fun log(tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), tag, message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        android.util.Log.i(LOGCAT_TAG, entry.formatted())
    }

    fun clear() { _entries.value = emptyList() }
}
