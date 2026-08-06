from __future__ import annotations

import logging
from typing import Any

from ecosystem_hub.core.protocol import CommandName, SessionState, ErrorCode
from ecosystem_hub.plugins.base import HubPlugin, HubPluginError

logger = logging.getLogger("ecosystem_hub.plugin.camera")


class CameraPlugin(HubPlugin):
    handled_commands = {
        CommandName.TAKE_PHOTO.value,
        CommandName.CAMERA_STREAM_START.value,
        CommandName.CAMERA_STREAM_STOP.value,
    }

    def __init__(self, context):
        super().__init__(context)
        # Register storage categories so StorageManager needs no hardcoding.
        self.ctx.register_storage_category("camera_snapshot", "camera")
        self.ctx.register_storage_category("camera_stream", "camera")

    async def handle_command(self, device_id: str, command: str, params: dict[str, Any]) -> dict[str, Any]:
        caps = self.ctx.capability_registry.get(device_id)
        required_capability = "camera.stream" if command in {
            CommandName.CAMERA_STREAM_START.value, CommandName.CAMERA_STREAM_STOP.value
        } else "camera.snapshot"
        if not caps or not caps.has(required_capability):
            raise HubPluginError(ErrorCode.CAPABILITY_NOT_FOUND.value,
                                  f"Device does not advertise {required_capability} capability")

        if command == CommandName.TAKE_PHOTO.value:
            return await self._take_photo(device_id, params)
        if command == CommandName.CAMERA_STREAM_START.value:
            return await self._stream_start(device_id, params)
        if command == CommandName.CAMERA_STREAM_STOP.value:
            return await self._stream_stop(device_id, params)

        raise HubPluginError(ErrorCode.INTERNAL_ERROR.value, f"unhandled command {command}")

    async def _take_photo(self, device_id: str, params: dict) -> dict:
        # A snapshot is modeled as a short-lived session so it participates
        # in the same lifecycle/monitoring machinery as streams.
        job = await self.ctx.job_manager.create(device_id, "camera.snapshot", "take_photo") if self.ctx.job_manager else None
        metadata = {**params, **({"job_id": job.job_id} if job else {})}
        session = await self.ctx.session_manager.create("camera_snapshot", device_id, metadata=metadata)
        await self.ctx.session_manager.transition(session.session_id, SessionState.RUNNING)
        if job:
            from ecosystem_hub.core.job_manager import JobState
            await self.ctx.job_manager.transition(job.job_id, JobState.RUNNING)

        from ecosystem_hub.core.protocol import Envelope, MessageType
        await self.ctx.send_to_device(device_id, Envelope(
            type=MessageType.COMMAND.value,
            payload={"command": CommandName.TAKE_PHOTO.value, "session_id": session.session_id, "params": params},
        ))
        await self.ctx.broadcast_to_ui({
            "type": "session_started",
            "session": session.to_dict(),
        })
        # Actual completion happens when the agent uploads the file via the
        # transfer server, which marks the session COMPLETED. See
        # transfer/file_transfer_server.py.
        return {"session_id": session.session_id, "job_id": job.job_id if job else None, "status": "requested"}

    async def _stream_start(self, device_id: str, params: dict) -> dict:
        existing = [s for s in self.ctx.session_manager.active_sessions(device_id) if s.session_type == "camera_stream"]
        if existing:
            raise HubPluginError(ErrorCode.SESSION_ALREADY_ACTIVE.value,
                                  "A camera stream session is already active for this device",
                                  session_id=existing[0].session_id)

        session = await self.ctx.session_manager.create("camera_stream", device_id, metadata=params)
        from ecosystem_hub.core.protocol import Envelope, MessageType
        await self.ctx.send_to_device(device_id, Envelope(
            type=MessageType.SESSION_CONTROL.value,
            payload={"action": "start", "session_type": "camera_stream",
                     "session_id": session.session_id, "params": params},
        ))
        await self.ctx.broadcast_to_ui({
            "type": "session_started",
            "session": session.to_dict(),
            "streaming_url": f"/stream/view/{device_id}/{session.session_id}",
        })
        return {"session_id": session.session_id, "status": "starting",
                "streaming_url": f"/stream/view/{device_id}/{session.session_id}"}

    async def _stream_stop(self, device_id: str, params: dict) -> dict:
        session_id = params.get("session_id")
        session = self.ctx.session_manager.get(session_id) if session_id else next(
            (s for s in self.ctx.session_manager.active_sessions(device_id) if s.session_type == "camera_stream"), None)
        if not session:
            raise HubPluginError(ErrorCode.SESSION_NOT_FOUND.value, "No such session")

        from ecosystem_hub.core.protocol import Envelope, MessageType
        await self.ctx.session_manager.transition(session.session_id, SessionState.STOPPING)
        await self.ctx.send_to_device(device_id, Envelope(
            type=MessageType.SESSION_CONTROL.value,
            payload={"action": "stop", "session_id": session.session_id,
                     "session_type": "camera_stream", "params": {}},
        ))
        await self.ctx.broadcast_to_ui({
            "type": "session_stopped",
            "session_id": session.session_id,
        })
        return {"session_id": session.session_id, "status": "stopping"}
