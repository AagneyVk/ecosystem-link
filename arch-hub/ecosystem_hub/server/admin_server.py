"""
Admin/control endpoint.

Small HTTP API, bound to the configured admin host (localhost by default),
that lets you POST a command and have it dispatched to a connected device —
the same way a CLI or automation rules engine would.

Kept deliberately separate from the WebSocket control plane and the file
transfer server so it can be replaced later without touching either.
"""

from __future__ import annotations

import logging

from aiohttp import web

logger = logging.getLogger("ecosystem_hub.admin")


class AdminServer:
    def __init__(self, *, dispatcher, device_manager,
                 host: str = "127.0.0.1", port: int = 8767):
        self.dispatcher = dispatcher
        self.device_manager = device_manager
        self.host = host
        self.port = port
        self.app = web.Application()
        self.app.router.add_get("/devices", self.list_devices)
        self.app.router.add_get("/sessions", self.list_sessions)
        self.app.router.add_post("/trigger/{device_id}/{command}", self.trigger_command)
        self._runner: web.AppRunner | None = None

    async def start(self) -> None:
        self._runner = web.AppRunner(self.app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.host, self.port)
        await site.start()
        logger.info("admin server listening on %s:%d (localhost only)", self.host, self.port)

    async def stop(self) -> None:
        if self._runner:
            await self._runner.cleanup()

    async def list_devices(self, request: web.Request) -> web.Response:
        devices = [
            {
                "device_id": d.device_id,
                "display_name": d.display_name,
                "connected": d.is_connected,
                "connected_at": d.connected_at,
                "runtime_state": d.runtime_state.to_dict(),
            }
            for d in self.device_manager.all_devices()
        ]
        return web.json_response({"devices": devices})

    async def list_sessions(self, request: web.Request) -> web.Response:
        sessions = [s.to_dict() for s in self.dispatcher.session_manager.active_sessions()]
        return web.json_response({"sessions": sessions})

    async def trigger_command(self, request: web.Request) -> web.Response:
        device_id = request.match_info["device_id"]
        command = request.match_info["command"]

        device = self.device_manager.get(device_id)
        if not device or not device.is_connected:
            return web.json_response(
                {"error": "DEVICE_OFFLINE", "message": f"Device '{device_id}' is not connected"},
                status=404,
            )

        try:
            params = await request.json()
        except Exception:
            params = {}

        try:
            result = await self.dispatcher.dispatch(device_id, command, params)
            return web.json_response({"status": "dispatched", "result": result})
        except Exception as e:
            logger.exception("admin-triggered command failed")
            return web.json_response({"error": "DISPATCH_FAILED", "message": str(e)}, status=500)
