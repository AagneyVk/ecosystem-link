import json

import pytest

from ecosystem_hub.core.capability_registry import CapabilityRegistry
from ecosystem_hub.core.protocol import Envelope, MessageType
from ecosystem_hub.server.websocket_server import WebSocketHub


class FakeSocket:
    def __init__(self, incoming):
        self.incoming = incoming
        self.sent = []
        self.closed = None

    async def recv(self):
        return self.incoming

    async def send(self, value):
        self.sent.append(json.loads(value))

    async def close(self, code, reason):
        self.closed = (code, reason)


class Devices:
    def get_or_create(self, device_id, display_name):
        return type("Device", (), {"runtime_state": type("State", (), {"update": lambda self, value: None})()})()


@pytest.mark.asyncio
async def test_current_android_handshake_is_accepted_without_optional_secret():
    capabilities = CapabilityRegistry()
    payload = Envelope(type=MessageType.HANDSHAKE.value, payload={
        "device_id": "phone-1", "display_name": "Phone",
        "capabilities": [{"name": "camera.snapshot", "permission_granted": True}],
        "runtime_state": {"battery_percent": 50},
    })
    socket = FakeSocket(json.dumps(payload.to_dict()))
    hub = WebSocketHub(dispatcher=None, capability_registry=capabilities,
                       device_manager=Devices(), session_manager=None,
                       host="127.0.0.1", shared_secret=None)

    assert await hub._await_handshake(socket) == "phone-1"
    assert socket.closed is None
    assert socket.sent[0]["type"] == MessageType.HANDSHAKE_ACK.value
    assert capabilities.get("phone-1").has("camera.snapshot")
