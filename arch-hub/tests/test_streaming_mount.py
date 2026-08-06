from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
import asyncio
import pytest

from ecosystem_hub.streaming.streaming_server import StreamingServer


def test_streaming_relay_mounts_on_shared_transfer_app_without_session_dependency():
    app = web.Application()
    relay = StreamingServer(host="127.0.0.1", port=8769)
    relay.mount_on(app)

    paths = {resource.canonical for resource in app.router.resources()}
    assert "/stream/source/{device_id}/{session_id}" in paths
    assert "/stream/view/{device_id}/{session_id}" in paths
    assert "/stream/status" in paths
    assert relay.active_streams() == []


@pytest.mark.asyncio
async def test_binary_frame_relay_works_without_hub_session_record():
    app = web.Application()
    relay = StreamingServer(host="127.0.0.1", port=8769)
    relay.mount_on(app)
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        viewer = await client.ws_connect("/stream/view/phone-1/free-pairing-id")
        source = await client.ws_connect("/stream/source/phone-1/free-pairing-id")
        frame = b"\xff\xd8ecosystem-test-jpeg\xff\xd9"
        await source.send_bytes(frame)
        message = await asyncio.wait_for(viewer.receive(), timeout=1)
        assert message.data == frame
        await source.close()
        await viewer.close()
    finally:
        await client.close()
