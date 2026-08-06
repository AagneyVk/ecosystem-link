package com.ecosystem.agent.session

import com.ecosystem.agent.net.SessionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors the hub's session lifecycle on the agent side so the agent can
 * report accurate `session_event` messages and answer local status
 * queries without round-tripping to the hub. Source of truth for
 * "which sessions/jobs are running" on this device, feeding the runtime
 * state reporter.
 */
data class AgentSession(
    val sessionId: String,
    val sessionType: String,
    var state: String = SessionState.PENDING,
    var lastError: String? = null,
)

class SessionManager {
    private val sessions = ConcurrentHashMap<String, AgentSession>()
    private val mutex = Mutex()
    private val listeners = mutableListOf<suspend (AgentSession) -> Unit>()

    fun onSessionChanged(listener: suspend (AgentSession) -> Unit) {
        listeners.add(listener)
    }

    suspend fun create(sessionId: String, sessionType: String): AgentSession {
        val session = AgentSession(sessionId, sessionType)
        mutex.withLock { sessions[sessionId] = session }
        notifyListeners(session)
        return session
    }

    suspend fun transition(sessionId: String, newState: String, error: String? = null) {
        val session = sessions[sessionId] ?: return
        mutex.withLock {
            session.state = newState
            if (error != null) session.lastError = error
        }
        notifyListeners(session)
    }

    fun activeSessions(): List<AgentSession> =
        sessions.values.filter { it.state in setOf(SessionState.PENDING, SessionState.RUNNING, SessionState.STOPPING) }

    fun get(sessionId: String): AgentSession? = sessions[sessionId]

    private suspend fun notifyListeners(session: AgentSession) {
        listeners.forEach { it(session) }
    }
}
