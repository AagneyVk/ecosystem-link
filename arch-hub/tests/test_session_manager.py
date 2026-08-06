import asyncio
import pytest

from ecosystem_hub.core.session_manager import SessionManager
from ecosystem_hub.core.protocol import SessionState


@pytest.mark.asyncio
async def test_create_and_transition():
    sm = SessionManager()
    session = await sm.create("camera_stream", "device-1")
    assert session.state == SessionState.PENDING

    await sm.transition(session.session_id, SessionState.RUNNING)
    assert sm.get(session.session_id).state == SessionState.RUNNING


@pytest.mark.asyncio
async def test_fail_all_for_device_on_disconnect():
    sm = SessionManager()
    s1 = await sm.create("camera_stream", "device-1")
    await sm.transition(s1.session_id, SessionState.RUNNING)
    s2 = await sm.create("microphone_stream", "device-2")
    await sm.transition(s2.session_id, SessionState.RUNNING)

    await sm.fail_all_for_device("device-1")

    assert sm.get(s1.session_id).state == SessionState.FAILED
    assert sm.get(s1.session_id).last_error == "device_disconnected"
    # unaffected device
    assert sm.get(s2.session_id).state == SessionState.RUNNING


@pytest.mark.asyncio
async def test_failure_handler_invoked():
    sm = SessionManager()
    called = {}

    async def handler(session, error):
        called["session_id"] = session.session_id
        called["error"] = error

    sm.on_failure(handler)
    s = await sm.create("camera_stream", "device-1")
    await sm.transition(s.session_id, SessionState.FAILED, error="camera_foreground_required")

    assert called["session_id"] == s.session_id
    assert called["error"] == "camera_foreground_required"


@pytest.mark.asyncio
async def test_active_sessions_filters_terminal_states():
    sm = SessionManager()
    s1 = await sm.create("camera_stream", "device-1")
    await sm.transition(s1.session_id, SessionState.COMPLETED)
    s2 = await sm.create("camera_stream", "device-1")
    await sm.transition(s2.session_id, SessionState.RUNNING)

    active = sm.active_sessions("device-1")
    assert len(active) == 1
    assert active[0].session_id == s2.session_id
