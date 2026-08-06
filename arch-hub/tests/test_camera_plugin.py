import pytest

from ecosystem_hub.core.capability_registry import CapabilityRegistry
from ecosystem_hub.core.session_manager import SessionManager
from ecosystem_hub.plugins.base import PluginContext, HubPluginError
from ecosystem_hub.plugins.camera_plugin import CameraPlugin


class MockDeviceManager:
    def get(self, device_id):
        return None


class MockStorageManager:
    def __init__(self):
        self.categories = {}

    def register_category(self, session_type, category):
        self.categories[session_type] = category


def make_ctx():
    cap_reg = CapabilityRegistry()
    cap_reg.get_or_create("device-1").update_from_list([
        {"name": "camera.snapshot", "permission_granted": True},
        {"name": "camera.stream", "permission_granted": True},
    ])
    session_manager = SessionManager()
    sent_messages = []

    async def sender(device_id, envelope):
        sent_messages.append((device_id, envelope))

    ctx = PluginContext(
        session_manager=session_manager,
        capability_registry=cap_reg,
        device_manager=MockDeviceManager(),
        storage_manager=MockStorageManager(),
        message_sender=sender,
        transfer_server=None,
    )
    return ctx, sent_messages


@pytest.mark.asyncio
async def test_take_photo_creates_session_and_sends_command():
    ctx, sent = make_ctx()
    plugin = CameraPlugin(ctx)

    result = await plugin.handle_command("device-1", "take_photo", {})
    assert "session_id" in result
    assert len(sent) == 1
    device_id, envelope = sent[0]
    assert device_id == "device-1"
    assert envelope.payload["command"] == "take_photo"


@pytest.mark.asyncio
async def test_missing_capability_raises():
    ctx, _ = make_ctx()
    plugin = CameraPlugin(ctx)
    with pytest.raises(HubPluginError):
        await plugin.handle_command("device-without-camera", "take_photo", {})


@pytest.mark.asyncio
async def test_double_stream_start_rejected():
    ctx, _ = make_ctx()
    plugin = CameraPlugin(ctx)
    await plugin.handle_command("device-1", "camera_stream_start", {})
    with pytest.raises(HubPluginError):
        await plugin.handle_command("device-1", "camera_stream_start", {})
