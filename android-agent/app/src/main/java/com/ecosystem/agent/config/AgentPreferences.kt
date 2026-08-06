package com.ecosystem.agent.config

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Wraps SharedPreferences for storing the hub URLs and device name entered
 * by the user during setup.
 */
class AgentPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ecosystem_agent_prefs", Context.MODE_PRIVATE)

    fun wsUrl(): String = prefs.getString(KEY_WS_URL, "") ?: ""
    fun transferBaseUrl(): String = prefs.getString(KEY_TRANSFER_URL, "") ?: ""
    fun displayName(): String = prefs.getString(KEY_DISPLAY_NAME, Build.MODEL ?: "android-device") ?: ""

    fun deviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun saveSetup(wsUrl: String, transferUrl: String, displayName: String) {
        prefs.edit()
            .putString(KEY_WS_URL, wsUrl.trim().trimEnd('/'))
            .putString(KEY_TRANSFER_URL, transferUrl.trim().trimEnd('/'))
            .putString(KEY_DISPLAY_NAME, displayName.trim())
            .apply()
    }

    fun clearConfig() {
        prefs.edit()
            .remove(KEY_WS_URL)
            .remove(KEY_TRANSFER_URL)
            .remove(KEY_DISPLAY_NAME)
            .apply()
    }

    fun isConfigured(): Boolean {
        val ws = wsUrl()
        val transfer = transferBaseUrl()
        // Validate that both URLs are non-blank and look like proper URLs.
        return ws.isNotBlank() && (ws.startsWith("ws://") || ws.startsWith("wss://")) &&
               transfer.isNotBlank() && (transfer.startsWith("http://") || transfer.startsWith("https://"))
    }

    companion object {
        private const val KEY_WS_URL = "ws_url"
        private const val KEY_TRANSFER_URL = "transfer_url"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
