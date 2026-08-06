package com.ecosystem.agent.session

import com.ecosystem.agent.net.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {

    @Test
    fun `create then transition updates state`() = runBlocking {
        val manager = SessionManager()
        val session = manager.create("session-1", "camera_snapshot")
        assertEquals(SessionState.PENDING, session.state)

        manager.transition("session-1", SessionState.RUNNING)
        assertEquals(SessionState.RUNNING, manager.get("session-1")?.state)
    }

    @Test
    fun `active sessions excludes terminal states`() = runBlocking {
        val manager = SessionManager()
        manager.create("s1", "camera_snapshot")
        manager.transition("s1", SessionState.COMPLETED)

        manager.create("s2", "microphone_stream")
        manager.transition("s2", SessionState.RUNNING)

        val active = manager.activeSessions()
        assertEquals(1, active.size)
        assertEquals("s2", active[0].sessionId)
    }

    @Test
    fun `listener notified on transition`() = runBlocking {
        val manager = SessionManager()
        var notifiedState: String? = null
        manager.onSessionChanged { session -> notifiedState = session.state }

        manager.create("s1", "camera_snapshot")
        manager.transition("s1", SessionState.FAILED, "camera_foreground_required")

        assertEquals(SessionState.FAILED, notifiedState)
        assertEquals("camera_foreground_required", manager.get("s1")?.lastError)
    }
}
