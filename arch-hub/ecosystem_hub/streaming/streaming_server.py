"""
Streaming server — MJPEG over WebSocket relay.

Architecture:
    Android → (binary WS frames, JPEG) → /source/{device_id}/{session_id}
    Browser ← (binary WS frames, JPEG) ← /view/{device_id}/{session_id}

The hub acts as a pure relay: it receives JPEG frames from Android on the
source endpoint and fans them out to all connected browser viewers on the
view endpoint for the same session. This keeps the streaming data plane
completely separate from the command/control WebSocket and the HTTP file
transfer channel.

Why MJPEG over WebSocket?
- Zero dependencies beyond what's already in requirements.txt (aiohttp handles WS)
- Browser displays frames natively via canvas or <img> blob URLs
- LAN-appropriate latency: each frame is sent as soon as it's captured
- No codec negotiation, no ICE/STUN, no browser security sandbox issues
- Easily upgradable: replace the source endpoint with an RTP/H.264 demuxer
  later without changing the view endpoint or browser code
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from aiohttp import web, WSMsgType

logger = logging.getLogger("ecosystem_hub.streaming")


class StreamingServer:
    """MJPEG-over-WebSocket relay server."""

    def __init__(self, *, host: str = "0.0.0.0", port: int = 8769):
        self.host = host
        self.port = port
        self.app = web.Application()
        self.app.router.add_get("/source/{device_id}/{session_id}", self._handle_source)
        self.app.router.add_get("/view/{device_id}/{session_id}", self._handle_view)
        self.app.router.add_get("/status", self._handle_status)

        # session_id -> list of viewer WebSocket connections
        self._viewers: dict[str, list[web.WebSocketResponse]] = {}
        # session_id -> source WebSocket (one per session)
        self._sources: dict[str, web.WebSocketResponse] = {}
        self._runner: web.AppRunner | None = None
        self._lock = asyncio.Lock()

    def mount_on(self, app: web.Application, prefix: str = "/stream") -> None:
        """Mount the relay on an existing phone-reachable HTTP server.

        Port 8766 is already required for verified transfers. Sharing it
        avoids a second VPN/firewall dependency for live media.
        """
        app.router.add_get(f"{prefix}/source/{{device_id}}/{{session_id}}", self._handle_source)
        app.router.add_get(f"{prefix}/view/{{device_id}}/{{session_id}}", self._handle_view)
        app.router.add_get(f"{prefix}/status", self._handle_status)

    async def start(self) -> None:
        self._runner = web.AppRunner(self.app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.host, self.port)
        await site.start()
        logger.info("streaming server listening on %s:%d", self.host, self.port)

    async def stop(self) -> None:
        if self._runner:
            await self._runner.cleanup()

    def active_streams(self) -> list[dict[str, Any]]:
        """Return a list of currently active stream sessions for UI display."""
        return [
            {
                "session_id": sid,
                "viewer_count": len(viewers),
                "has_source": sid in self._sources,
            }
            for sid, viewers in self._viewers.items()
        ]

    async def _handle_source(self, request: web.Request) -> web.WebSocketResponse:
        """Android agent connects here to push JPEG frames."""
        device_id = request.match_info["device_id"]
        session_id = request.match_info["session_id"]

        ws = web.WebSocketResponse(max_msg_size=4 * 1024 * 1024)
        await ws.prepare(request)

        async with self._lock:
            # Close any existing source for this session
            old = self._sources.get(session_id)
            if old and not old.closed:
                await old.close()
            self._sources[session_id] = ws
            if session_id not in self._viewers:
                self._viewers[session_id] = []

        logger.info("stream source connected: device=%s session=%s", device_id, session_id)

        try:
            async for msg in ws:
                if msg.type == WSMsgType.BINARY:
                    # Relay JPEG frame to all active viewers
                    await self._relay_frame(session_id, msg.data)
                elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
        finally:
            async with self._lock:
                self._sources.pop(session_id, None)
            logger.info("stream source disconnected: session=%s", session_id)

        return ws

    async def _handle_view(self, request: web.Request) -> web.WebSocketResponse:
        """Browser connects here to receive JPEG frames for a session."""
        session_id = request.match_info["session_id"]

        ws = web.WebSocketResponse()
        await ws.prepare(request)

        async with self._lock:
            if session_id not in self._viewers:
                self._viewers[session_id] = []
            self._viewers[session_id].append(ws)

        logger.info("stream viewer connected: session=%s (total viewers: %d)",
                    session_id, len(self._viewers.get(session_id, [])))

        try:
            # Keep connection alive; frames arrive via _relay_frame
            async for msg in ws:
                if msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
        finally:
            async with self._lock:
                viewers = self._viewers.get(session_id, [])
                if ws in viewers:
                    viewers.remove(ws)
            logger.info("stream viewer disconnected: session=%s", session_id)

        return ws

    async def _relay_frame(self, session_id: str, frame: bytes) -> None:
        """Send a JPEG frame to all viewers of a session."""
        viewers = self._viewers.get(session_id, [])
        if not viewers:
            return
        dead = []
        for viewer in viewers:
            try:
                await viewer.send_bytes(frame)
            except Exception:
                dead.append(viewer)
        if dead:
            async with self._lock:
                current = self._viewers.get(session_id, [])
                self._viewers[session_id] = [v for v in current if v not in dead]

    async def _handle_status(self, request: web.Request) -> web.Response:
        return web.json_response({"active_streams": self.active_streams()})
