package com.pyramidrelay

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pyramid_relay_settings", Context.MODE_PRIVATE)

    var relayNonSubscribed: Boolean
        get() = prefs.getBoolean(KEY_RELAY_NON_SUBSCRIBED, true)
        set(value) = prefs.edit().putBoolean(KEY_RELAY_NON_SUBSCRIBED, value).apply()

    companion object {
        private const val KEY_RELAY_NON_SUBSCRIBED = "relay_non_subscribed"
    }
}
