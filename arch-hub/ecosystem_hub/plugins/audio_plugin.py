from __future__ import annotations

from typing import Any

from ecosystem_hub.core.protocol import CommandName, SessionState, ErrorCode, Envelope, MessageType
from ecosystem_hub.plugins.base import HubPlugin, HubPluginError


class AudioPlugin(HubPlugin):
    handled_commands = {
        CommandName.RECORD_AUDIO.value,
        CommandName.MIC_STREAM_START.value,
        CommandName.MIC_STREAM_STOP.value,
    }

    def __init__(self, context):
        super().__init__(context)
        # Register storage categories so StorageManager needs no hardcoding.
        self.ctx.register_storage_category("microphone_record", "audio")
        self.ctx.register_storage_category("microphone_stream", "audio")

    async def handle_command(self, device_id: str, command: str, params: dict[str, Any]) -> dict[str, Any]:
        caps = self.ctx.capability_registry.get(device_id)
        if not caps or not caps.has("microphone.record"):
            raise HubPluginError(ErrorCode.CAPABILITY_NOT_FOUND.value,
                                  "Device does not advertise microphone.record capability")

        if command == CommandName.RECORD_AUDIO.value:
            return await self._record(device_id, params)
        if command == CommandName.MIC_STREAM_START.value:
            return await self._stream_start(device_id, params)
        if command == CommandName.MIC_STREAM_STOP.value:
            return await self._stream_stop(device_id, params)
        raise HubPluginError(ErrorCode.INTERNAL_ERROR.value, f"unhandled command {command}")

    async def _record(self, device_id: str, params: dict) -> dict:
        session = await self.ctx.session_manager.create("microphone_record", device_id, metadata=params)
        await self.ctx.session_manager.transition(session.session_id, SessionState.RUNNING)
        await self.ctx.send_to_device(device_id, Envelope(
            type=MessageType.COMMAND.value,
            payload={"command": CommandName.RECORD_AUDIO.value, "session_id": session.session_id, "params": params},
        ))
        await self.ctx.broadcast_to_ui({"type": "session_started", "session": session.to_dict()})
        return {"session_id": session.session_id, "status": "requested"}

    async def _stream_start(self, device_id: str, params: dict) -> dict:
        existing = [s for s in self.ctx.session_manager.active_sessions(device_id) if s.session_type == "microphone_stream"]
        if existing:
            raise HubPluginError(ErrorCode.SESSION_ALREADY_ACTIVE.value,
                                  "A microphone stream session is already active", session_id=existing[0].session_id)
        session = await self.ctx.session_manager.create("microphone_stream", device_id, metadata=params)
        await self.ctx.send_to_device(device_id, Envelope(
            type=MessageType.SESSION_CONTROL.value,
            payload={"action": "start", "session_type": "microphone_stream",
                     "session_id": session.session_id, "params": params},
        ))
        await self.ctx.broadcast_to_ui({"type": "session_started", "session": session.to_dict()})
        return {"session_id": session.session_id, "status": "starting"}

    async def _stream_stop(self, device_id: str, params: dict) -> dict:
        session_id = params.get("session_id")
        session = self.ctx.session_manager.get(session_id) if session_id else next(
            (s for s in self.ctx.session_manager.active_sessions(device_id) if s.session_type == "microphone_stream"), None)
        if not session:
            raise HubPluginError(ErrorCode.SESSION_NOT_FOUND.value, "No such session")
        await self.ctx.session_manager.transition(session.session_id, SessionState.STOPPING)
        await self.ctx.send_to_device(device_id, Envelope(
            type=MessageType.SESSION_CONTROL.value,
            payload={"action": "stop", "session_id": session.session_id,
                     "session_type": "microphone_stream", "params": {}},
        ))
        await self.ctx.broadcast_to_ui({"type": "session_stopped", "session_id": session.session_id})
        return {"session_id": session.session_id, "status": "stopping"}
