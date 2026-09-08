package com.pyramidrelay

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pyramid_relay_settings", Context.MODE_PRIVATE)

    // NOTE: there is intentionally no toggle for the foreground notification.
    // Background relay is the core feature and the service always runs as a
    // user-perceptible foreground service (Play FGS policy).

    /** Prominent disclosure (User Data policy) accepted before runtime permissions. */
    var dataDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DATA_DISCLOSURE, false)
        set(value) = prefs.edit().putBoolean(KEY_DATA_DISCLOSURE, value).apply()

    /** UGC terms / EULA accepted before broadcasting or subscribing. */
    var termsAccepted: Boolean
        get() = prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, value).apply()

    /** User-blocked file IDs (UGC moderation). Never advertised, fetched, or relayed. */
    var blockedFileIds: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED_IDS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLOCKED_IDS, value).apply()

    fun isBlocked(fileId: String): Boolean = blockedFileIds.contains(fileId)

    fun blockFile(fileId: String) {
        blockedFileIds = blockedFileIds + fileId
    }

    companion object {
        private const val KEY_DATA_DISCLOSURE = "data_disclosure_accepted"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted"
        private const val KEY_BLOCKED_IDS = "blocked_file_ids"
    }
}
