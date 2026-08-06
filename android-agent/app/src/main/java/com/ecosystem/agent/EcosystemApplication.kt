package com.ecosystem.agent

import android.app.Application
import android.content.Intent
import android.os.Build
import com.ecosystem.agent.config.AgentPreferences
import com.ecosystem.agent.service.EcosystemAgentService

class EcosystemApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = AgentPreferences(this)
        if (prefs.isConfigured()) {
            val intent = Intent(this, EcosystemAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}
