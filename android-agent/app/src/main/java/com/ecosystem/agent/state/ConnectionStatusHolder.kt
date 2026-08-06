package com.ecosystem.agent.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * Holds live connection status and logs for display in the UI.
 * Used by EcosystemWebSocketClient to report connection events
 * and by the CompanionActivity status screen to display them.
 */
object ConnectionStatusHolder {
    private val _status = MutableStateFlow<String>("Initializing...")
    val status: StateFlow<String> = _status

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val _connected = MutableStateFlow<Boolean>(false)
    val connected: StateFlow<Boolean> = _connected

    data class LogEntry(
        val timestamp: String,
        val level: String, // INFO, ERROR, DEBUG, WARNING
        val message: String,
    )

    fun setStatus(message: String) {
        _status.value = message
        addLog("INFO", message)
    }

    fun setError(message: String) {
        _status.value = "❌ $message"
        addLog("ERROR", message)
    }

    fun setConnected(connected: Boolean) {
        _connected.value = connected
        if (connected) {
            setStatus("✅ Connected to hub!")
        }
    }

    fun addLog(level: String, message: String) {
        val entry = LogEntry(
            timestamp = Instant.now().toString().substringAfterLast('T').take(12),
            level = level,
            message = message,
        )
        val current = _logs.value.toMutableList()
        current.add(entry)
        // Keep only last 30 logs to avoid memory issues
        if (current.size > 30) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun clear() {
        _logs.value = emptyList()
        _status.value = "Initializing..."
        _connected.value = false
    }
}
