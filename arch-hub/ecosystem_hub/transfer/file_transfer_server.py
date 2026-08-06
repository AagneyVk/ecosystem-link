"""
File transfer server.

Media artifacts (photos, audio clips, and future video/file-handoff
payloads) are never sent through the WebSocket control channel. Instead:

    1. The Android agent captures to a temp file locally.
    2. It computes a SHA-256 checksum.
    3. It HTTP PUTs the file to this server at
       /upload/<device_id>/<session_id>/<filename>
       with header X-Checksum-SHA256.
    4. The server verifies the checksum, writes the artifact via
       StorageManager, marks the session COMPLETED, and responds 200 with
       an acknowledgement body.
    5. Only after receiving that 200 ack does the Android agent delete its
       local temp copy -- the phone is a temporary sensor node, not a
       storage node, and we don't want to lose the only copy if the ack
       itself is lost in transit.

This lives on a plain aiohttp server on its own port, decoupled from the
WebSocket control plane, so large binary transfers never contend with or
block command/control latency.
"""

from __future__ import annotations

import logging
from typing import Any

from aiohttp import web

from ecosystem_hub.core.protocol import SessionState

logger = logging.getLogger("ecosystem_hub.transfer")


class FileTransferServer:
    def __init__(self, *, storage_manager, session_manager, host: str = "0.0.0.0", port: int = 8766,
                 max_upload_bytes: int = 500 * 1024 * 1024, job_manager=None):
        self.storage_manager = storage_manager
        self.session_manager = session_manager
        self.host = host
        self.port = port
        self.max_upload_bytes = max_upload_bytes
        self.job_manager = job_manager
        self.app = web.Application(client_max_size=max_upload_bytes)
        self.app.router.add_put("/upload/{device_id}/{session_id}/{filename}", self.handle_upload)
        self.app.router.add_get("/health", self.handle_health)
        self.app.router.add_get("/outbound/{device_id}/{checksum}/{filename}", self.handle_outbound)
        self._runner: web.AppRunner | None = None
        self.on_complete = None

    async def start(self) -> None:
        self._runner = web.AppRunner(self.app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.host, self.port)
        await site.start()
        logger.info("file transfer server listening on %s:%d", self.host, self.port)

    async def stop(self) -> None:
        if self._runner:
            await self._runner.cleanup()

    async def handle_health(self, request: web.Request) -> web.Response:
        return web.json_response({
            "status": "ok",
            "service": "ecosystem-transfer",
            "protocol_version": 1,
            "streaming_status": "/stream/status",
        })

    async def handle_outbound(self, request: web.Request) -> web.StreamResponse:
        device = self.storage_manager.safe_component(request.match_info["device_id"], "device")
        filename = self.storage_manager.safe_component(request.match_info["filename"], "file.bin")
        checksum = request.match_info["checksum"].lower()
        base = self.storage_manager.root / device / "outbound"
        if len(checksum) != 64 or not base.exists():
            raise web.HTTPNotFound()
        for candidate in base.rglob(filename):
            if candidate.is_file() and self.storage_manager.sha256_of_file(candidate) == checksum:
                return web.FileResponse(candidate, headers={"Content-Disposition": f'attachment; filename="{filename}"'})
        raise web.HTTPNotFound()

    async def handle_upload(self, request: web.Request) -> web.Response:
        device_id = request.match_info["device_id"]
        session_id = request.match_info["session_id"]
        filename = request.match_info["filename"]
        claimed_checksum = request.headers.get("X-Checksum-SHA256", "")
        logger.info("[Upload] Received upload request device=%s session=%s filename=%s", device_id, session_id, filename)

        session = self.session_manager.get(session_id)
        requested_type = request.headers.get("X-Session-Type", "")
        if not session and requested_type == "manual_file":
            session = await self.session_manager.create(
                "manual_file", device_id, metadata={"source": "android_file_picker"},
                session_id=session_id,
            )
        if not session:
            logger.warning("[Upload] Unknown session_id=%s", session_id)
            return web.json_response({"error": "SESSION_NOT_FOUND", "message": "Unknown session_id"}, status=404)

        if session.device_id != device_id:
            logger.warning(
                "[Upload] Device mismatch for session %s: route device=%s session device=%s",
                session_id,
                device_id,
                session.device_id,
            )
            return web.json_response({
                "error": "DEVICE_MISMATCH",
                "message": "Upload device_id does not match the session owner.",
            }, status=409)

        data = await request.read()
        actual_checksum = self.storage_manager.sha256_of(data)
        logger.debug("[Upload] Payload size=%d checksum=%s", len(data), actual_checksum)

        if claimed_checksum and claimed_checksum.lower() != actual_checksum.lower():
            await self.session_manager.transition(session_id, SessionState.FAILED, error="checksum_mismatch")
            logger.warning("checksum mismatch for session %s: claimed=%s actual=%s",
                            session_id, claimed_checksum, actual_checksum)
            return web.json_response({
                "error": "CHECKSUM_MISMATCH",
                "message": "Uploaded file checksum did not match claimed checksum. File was NOT stored; "
                            "do not delete the local copy.",
            }, status=409)

        dest_path = self.storage_manager.write_artifact(device_id, session.session_type, filename, data)
        self.storage_manager.write_metadata(device_id, session_id, {
            "session": session.to_dict(),
            "filename": filename,
            "sha256": actual_checksum,
            "size_bytes": len(data),
            "stored_path": str(dest_path),
            "upload_device_id": device_id,
        })

        await self.session_manager.transition(session_id, SessionState.COMPLETED)
        job_id = session.metadata.get("job_id")
        if job_id and self.job_manager:
            from ecosystem_hub.core.job_manager import JobState
            await self.job_manager.transition(job_id, JobState.COMPLETED,
                                              result={"path": str(dest_path), "sha256": actual_checksum})
        if self.on_complete:
            await self.on_complete({"type": "media_updated", "device_id": device_id,
                                    "session_id": session_id, "filename": dest_path.name})
        logger.info("[Upload] Stored artifact for session=%s at %s", session_id, dest_path)

        return web.json_response({
            "status": "ack",
            "session_id": session_id,
            "sha256": actual_checksum,
            "size_bytes": len(data),
            "safe_to_delete_local_copy": True,
        }, status=200)
