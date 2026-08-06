package com.ecosystem.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ecosystem.agent.config.AgentPreferences
import com.ecosystem.agent.service.EcosystemAgentService

/**
 * Restarts the agent service after device reboot, but only if the user
 * has completed setup (has a shared secret configured) - per "automatically
 * reconnect after device reboot if configured by the user". Requires
 * RECEIVE_BOOT_COMPLETED, declared in the manifest.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = AgentPreferences(context)
        if (!prefs.isConfigured()) return

        val serviceIntent = Intent(context, EcosystemAgentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
