"""
Web UI server.

Serves the browser-based control dashboard and maintains a WebSocket
bridge between browser clients and the hub's internal state. Browsers
connect to ws://<hub>:<ui_port>/ws and receive push updates whenever
device state changes, sessions start/stop, or files are uploaded.

Browser → Hub messages (commands):
    { "type": "command", "device_id": "...", "command": "take_photo", "params": {} }
    { "type": "ping" }

Hub → Browser messages (push):
    { "type": "full_state", "devices": [...], "sessions": [...], "streams": [...] }
    { "type": "device_connected", "device": {...} }
    { "type": "device_disconnected", "device_id": "..." }
    { "type": "session_started", "session": {...} }
    { "type": "session_stopped", "session_id": "..." }
    { "type": "session_completed", "session": {...} }
    { "type": "files_updated", "device_id": "...", "files": [...] }
    { "type": "pong" }
"""

from __future__ import annotations

import asyncio
import json
import logging
import pathlib
import hashlib
from typing import Any

from aiohttp import web, WSMsgType

logger = logging.getLogger("ecosystem_hub.ui")

STATIC_DIR = pathlib.Path(__file__).parent / "static"
# Always ensure the static directory exists so aiohttp's add_static doesn't
# crash if the zip transfer omitted the (initially empty) directory.
STATIC_DIR.mkdir(parents=True, exist_ok=True)


class UIServer:
    def __init__(self, *, dispatcher, device_manager, session_manager,
                 capability_registry, storage_manager, job_manager=None, media_index=None, streaming_server=None, transfer_server=None,
                 host: str = "127.0.0.1", port: int = 8768):
        self.dispatcher = dispatcher
        self.device_manager = device_manager
        self.session_manager = session_manager
        self.capability_registry = capability_registry
        self.storage_manager = storage_manager
        self.job_manager = job_manager
        self.media_index = media_index
        self.streaming_server = streaming_server
        self.transfer_server = transfer_server
        self.host = host
        self.port = port

        self.app = web.Application(client_max_size=100 * 1024 * 1024)
        self.app.router.add_get("/ws", self._handle_ws)
        self.app.router.add_get("/api/state", self._api_state)
        self.app.router.add_get("/api/files/{device_id}", self._api_files)
        self.app.router.add_get("/api/files/{device_id}/{file_id}/content", self._api_file_content)
        self.app.router.add_post("/api/send-file/{device_id}", self._api_send_file)
        self.app.router.add_get("/api/media", self._api_media)
        self.app.router.add_get("/api/media/{file_id}/content", self._api_media_content)
        self.app.router.add_delete("/api/media/{file_id}", self._api_media_delete)
        self.app.router.add_get("/", self._serve_index)
        # Only register the static route when the directory actually exists
        # (it always will after the mkdir above, but guard defensively).
        if STATIC_DIR.exists():
            self.app.router.add_static("/static", STATIC_DIR, name="static")

        self._clients: list[web.WebSocketResponse] = []
        self._runner: web.AppRunner | None = None
        self._lock = asyncio.Lock()

    async def start(self) -> None:
        self._runner = web.AppRunner(self.app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.host, self.port)
        await site.start()
        logger.info("web UI server listening on http://%s:%d", self.host, self.port)

    async def stop(self) -> None:
        if self._runner:
            await self._runner.cleanup()

    async def broadcast(self, message: dict[str, Any]) -> None:
        """Push a message to all connected browser clients."""
        if not self._clients:
            return
        payload = json.dumps(message)
        dead = []
        for ws in list(self._clients):
            try:
                await ws.send_str(payload)
            except Exception:
                dead.append(ws)
        if dead:
            async with self._lock:
                for ws in dead:
                    if ws in self._clients:
                        self._clients.remove(ws)

    # ── HTTP endpoints ────────────────────────────────────────────────────────

    async def _serve_index(self, request: web.Request) -> web.Response:
        index = STATIC_DIR / "index.html"
        if index.exists():
            return web.FileResponse(index)
        return web.Response(
            text="<h2>Ecosystem Hub UI</h2><p>Static files not found. "
                 "Make sure <code>ecosystem_hub/ui/static/</code> contains "
                 "<code>index.html</code>, <code>style.css</code>, and <code>app.js</code>.</p>",
            content_type="text/html",
            status=200,
        )

    async def _api_state(self, request: web.Request) -> web.Response:
        return web.json_response(self._build_full_state())

    async def _api_files(self, request: web.Request) -> web.Response:
        device_id = request.match_info["device_id"]
        files = self._list_files(device_id)
        return web.json_response({"device_id": device_id, "files": files})

    async def _api_file_content(self, request: web.Request) -> web.StreamResponse:
        device_id = request.match_info["device_id"]
        file_id = request.match_info["file_id"]
        for entry in self._list_files(device_id, include_internal_path=True):
            if entry["file_id"] == file_id:
                path = pathlib.Path(entry.pop("_path")).resolve()
                if path.is_relative_to(self.storage_manager.root.resolve()):
                    return web.FileResponse(path, headers={"Content-Disposition": f'inline; filename="{path.name}"'})
        raise web.HTTPNotFound(text="Unknown file")

    async def _api_send_file(self, request: web.Request) -> web.Response:
        device_id = request.match_info["device_id"]
        device = self.device_manager.get(device_id)
        if not device or not device.is_connected:
            raise web.HTTPConflict(text="Android device is not connected")
        if not self.transfer_server or self.transfer_server.host in {"0.0.0.0", "::"}:
            raise web.HTTPConflict(text="Set transfer_host to the Arch ZeroTier IP so Android can download outbound files")
        reader = await request.multipart()
        field = await reader.next()
        if field is None or not field.filename:
            raise web.HTTPBadRequest(text="Missing file")
        filename = self.storage_manager.safe_component(field.filename, "shared_file")
        data = await field.read(decode=False)
        if len(data) > 100 * 1024 * 1024:
            raise web.HTTPRequestEntityTooLarge(max_size=100 * 1024 * 1024, actual_size=len(data))
        path = self.storage_manager.write_artifact(device_id, "outbound_file", filename, data)
        checksum = self.storage_manager.sha256_of_file(path)
        url = f"http://{self.transfer_server.host}:{self.transfer_server.port}/outbound/{device_id}/{checksum}/{path.name}"
        result = await self.dispatcher.dispatch(device_id, "file_receive", {
            "url": url, "filename": filename, "sha256": checksum, "size_bytes": len(data),
        })
        return web.json_response({"status": "sent", "filename": filename, "size_bytes": len(data), "result": result})

    async def _api_media(self, request: web.Request) -> web.Response:
        if not self.media_index:
            return web.json_response({"media": []})
        device_id = request.query.get("device_id") or None
        items = await asyncio.to_thread(self.media_index.refresh, device_id)
        return web.json_response({"media": [item.to_dict() for item in items]})

    async def _api_media_content(self, request: web.Request) -> web.StreamResponse:
        if not self.media_index:
            raise web.HTTPNotFound()
        item = self.media_index.get(request.match_info["file_id"])
        if not item:
            await asyncio.to_thread(self.media_index.refresh)
            item = self.media_index.get(request.match_info["file_id"])
        if not item:
            raise web.HTTPNotFound(text="Unknown media item")
        return web.FileResponse(item.path, headers={"Cache-Control": "private, max-age=3600"})

    async def _api_media_delete(self, request: web.Request) -> web.Response:
        if not self.media_index or not self.media_index.delete(request.match_info["file_id"]):
            raise web.HTTPNotFound(text="Unknown media item")
        await self.broadcast({"type": "media_updated"})
        return web.json_response({"status": "deleted"})

    # ── WebSocket handler ─────────────────────────────────────────────────────

    async def _handle_ws(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)

        async with self._lock:
            self._clients.append(ws)

        # Send the full current state immediately on connect
        await ws.send_str(json.dumps({"type": "full_state", **self._build_full_state()}))

        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    await self._handle_browser_message(ws, msg.data)
                elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
        finally:
            async with self._lock:
                if ws in self._clients:
                    self._clients.remove(ws)

        return ws

    async def _handle_browser_message(self, ws: web.WebSocketResponse, raw: str) -> None:
        try:
            msg = json.loads(raw)
        except Exception:
            await ws.send_str(json.dumps({"type": "error", "message": "invalid JSON"}))
            return

        msg_type = msg.get("type")

        if msg_type == "ping":
            await ws.send_str(json.dumps({"type": "pong"}))
            return

        if msg_type == "command":
            device_id = msg.get("device_id")
            command = msg.get("command")
            params = msg.get("params", {})
            device = self.device_manager.get(device_id)
            if not device or not device.is_connected:
                await ws.send_str(json.dumps({
                    "type": "error", "message": f"Device '{device_id}' is not connected"
                }))
                return
            try:
                result = await self.dispatcher.dispatch(device_id, command, params)
                await ws.send_str(json.dumps({"type": "command_result", "command": command, "result": result}))
            except Exception as e:
                await ws.send_str(json.dumps({"type": "error", "message": str(e)}))
            return

        if msg_type == "get_files":
            device_id = msg.get("device_id")
            files = self._list_files(device_id)
            await ws.send_str(json.dumps({"type": "files_updated", "device_id": device_id, "files": files}))
            return

        await ws.send_str(json.dumps({"type": "error", "message": f"unknown message type '{msg_type}'"}))

    # ── State helpers ─────────────────────────────────────────────────────────

    def _build_full_state(self) -> dict[str, Any]:
        devices = []
        for d in self.device_manager.all_devices():
            caps = self.capability_registry.get(d.device_id)
            devices.append({
                "device_id": d.device_id,
                "display_name": d.display_name,
                "connected": d.is_connected,
                "connected_at": d.connected_at,
                "runtime_state": d.runtime_state.to_dict(),
                "capabilities": caps.to_dict()["capabilities"] if caps else [],
            })
        sessions = [s.to_dict() for s in self.session_manager.active_sessions()]
        streams = self.streaming_server.active_streams() if self.streaming_server else []
        jobs = [j.to_dict() for j in self.job_manager.all()] if self.job_manager else []
        streaming_endpoint = None
        if self.streaming_server and self.transfer_server:
            streaming_endpoint = f"ws://{self.transfer_server.host}:{self.transfer_server.port}/stream"
        return {"devices": devices, "jobs": jobs, "sessions": sessions, "streams": streams,
                "transfers": [], "streaming_endpoint": streaming_endpoint}

    def _list_files(self, device_id: str | None, include_internal_path: bool = False) -> list[dict[str, Any]]:
        if not device_id:
            return []
        files = []
        for path in self.storage_manager.list_artifacts(device_id):
            try:
                stat = path.stat()
                rel = path.relative_to(self.storage_manager.root)
                item = {
                    "file_id": hashlib.sha256(str(rel).encode("utf-8")).hexdigest()[:24],
                    "path": str(rel),
                    "name": path.name,
                    "size_bytes": stat.st_size,
                    "modified": stat.st_mtime,
                    "category": rel.parts[1] if len(rel.parts) > 1 else "misc",
                }
                if include_internal_path:
                    item["_path"] = str(path)
                files.append(item)
            except Exception:
                pass
        return files
