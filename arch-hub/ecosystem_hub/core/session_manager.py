"""
Session manager.

Long-running operations (camera_stream, microphone_stream, and future
session types like presence-tracking or file-handoff) are modeled as
Sessions with a defined lifecycle: pending -> running -> completed/failed/
cancelled.

This is deliberately decoupled from the WebSocket layer: sessions are plain
state objects with callbacks, so they can be driven by tests without a live
socket, and so future session types (non-Android, e.g. a local automation
job) can reuse the same lifecycle machinery.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional

from ecosystem_hub.core.protocol import SessionState

logger = logging.getLogger("ecosystem_hub.session")

FailureHandler = Callable[["Session", str], Awaitable[None]]


@dataclass
class Session:
    session_id: str
    session_type: str  # e.g. "camera_stream", "microphone_stream"
    device_id: str
    state: SessionState = SessionState.PENDING
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    metadata: dict[str, Any] = field(default_factory=dict)
    last_error: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "session_id": self.session_id,
            "session_type": self.session_type,
            "device_id": self.device_id,
            "state": self.state.value,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "metadata": self.metadata,
            "last_error": self.last_error,
        }


class SessionManager:
    """
    Tracks all active/recent sessions across all devices.

    Recovery model: if a device disconnects while a session is RUNNING, the
    session is transitioned to FAILED with last_error="device_disconnected"
    rather than being silently dropped, so operators/automations can react.
    """

    def __init__(self, retention_seconds: int = 3600):
        self._sessions: dict[str, Session] = {}
        self._retention_seconds = retention_seconds
        self._lock = asyncio.Lock()
        self._failure_handlers: list[FailureHandler] = []

    def on_failure(self, handler: FailureHandler) -> None:
        self._failure_handlers.append(handler)

    async def create(self, session_type: str, device_id: str, metadata: dict | None = None,
                     session_id: str | None = None) -> Session:
        async with self._lock:
            session = Session(
                session_id=session_id or str(uuid.uuid4()),
                session_type=session_type,
                device_id=device_id,
                metadata=metadata or {},
            )
            self._sessions[session.session_id] = session
            logger.info("session created id=%s type=%s device=%s", session.session_id, session_type, device_id)
            return session

    async def transition(self, session_id: str, new_state: SessionState, error: Optional[str] = None) -> Optional[Session]:
        async with self._lock:
            session = self._sessions.get(session_id)
            if not session:
                return None
            session.state = new_state
            session.updated_at = time.time()
            if error:
                session.last_error = error
            logger.info("session %s -> %s (error=%s)", session_id, new_state.value, error)

        if new_state == SessionState.FAILED and error:
            for handler in self._failure_handlers:
                try:
                    await handler(session, error)
                except Exception:
                    logger.exception("session failure handler raised")
        return session

    def get(self, session_id: str) -> Optional[Session]:
        return self._sessions.get(session_id)

    def active_sessions(self, device_id: Optional[str] = None) -> list[Session]:
        active_states = {SessionState.PENDING, SessionState.RUNNING, SessionState.STOPPING}
        result = [s for s in self._sessions.values() if s.state in active_states]
        if device_id:
            result = [s for s in result if s.device_id == device_id]
        return result

    async def fail_all_for_device(self, device_id: str, reason: str = "device_disconnected") -> None:
        """Called by the connection manager when a device drops off."""
        for session in self.active_sessions(device_id=device_id):
            await self.transition(session.session_id, SessionState.FAILED, error=reason)

    def prune_old(self) -> None:
        cutoff = time.time() - self._retention_seconds
        terminal = {SessionState.COMPLETED, SessionState.FAILED, SessionState.CANCELLED}
        stale = [sid for sid, s in self._sessions.items() if s.state in terminal and s.updated_at < cutoff]
        for sid in stale:
            del self._sessions[sid]
