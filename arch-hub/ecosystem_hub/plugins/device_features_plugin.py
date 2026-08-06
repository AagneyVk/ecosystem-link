from __future__ import annotations

from ecosystem_hub.core.protocol import Envelope, MessageType, ErrorCode
from ecosystem_hub.plugins.base import HubPlugin, HubPluginError


COMMAND_CAPABILITY = {
    "sensor_refresh": "device.state",
    "sensor_stream_start": None,
    "sensor_stream_stop": None,
    "location_current": "location.current",
    "location_stream_start": "location.stream",
    "location_stream_stop": "location.stream",
    "screen_prepare": "screen.record",
    "screen_record_start": "screen.record",
    "screen_record_stop": "screen.record",
    "screen_stream_start": "screen.record",
    "screen_stream_stop": "screen.record",
    "cancel_session": None,
    "clipboard_set": "clipboard.text",
    "clipboard_get": "clipboard.text",
    "clipboard_clear": "clipboard.text",
    "file_receive": "files.receive",
}


class DeviceFeaturesPlugin(HubPlugin):
    handled_commands = set(COMMAND_CAPABILITY)

    async def handle_command(self, device_id: str, command: str, params: dict) -> dict:
        if command == "cancel_session" and params.get("all"):
            stopped = []
            for session in list(self.ctx.session_manager.active_sessions(device_id)):
                envelope = Envelope(type=MessageType.SESSION_CONTROL.value,
                    payload={"action": "stop", "session_id": session.session_id,
                             "session_type": session.session_type, "params": {}})
                await self.ctx.send_to_device(device_id, envelope)
                stopped.append(session.session_id)
            await self.ctx.send_to_device(device_id, Envelope(type=MessageType.COMMAND.value,
                payload={"command": "screen_record_stop", "params": {}}))
            return {"status": "accepted", "stopping": stopped}
        capabilities = self.ctx.capability_registry.get(device_id)
        required = COMMAND_CAPABILITY[command]
        if command.startswith("sensor_") and command != "sensor_refresh":
            required = str(params.get("sensor", ""))
        if required and (not capabilities or not capabilities.has(required)):
            raise HubPluginError(ErrorCode.CAPABILITY_NOT_FOUND.value,
                                 f"Device does not advertise available capability '{required}'")
        payload = {"command": command, "params": params}
        session = None
        job = None
        if command in ("screen_prepare", "screen_record_start", "screen_stream_start"):
            if self.ctx.job_manager:
                from ecosystem_hub.core.job_manager import JobState
                job = await self.ctx.job_manager.create(device_id, "screen.record", command)
                await self.ctx.job_manager.transition(job.job_id, JobState.RUNNING)
            metadata = {**params, **({"job_id": job.job_id} if job else {})}
            session_type = "screen_stream" if command == "screen_stream_start" else "screen_recording"
            session = await self.ctx.session_manager.create(session_type, device_id, metadata=metadata)
            payload["session_id"] = session.session_id
        envelope = Envelope(type=MessageType.COMMAND.value, payload=payload, device_id=device_id)
        await self.ctx.send_to_device(device_id, envelope)
        return {"status": "accepted", "correlation_id": envelope.msg_id,
                "session_id": session.session_id if session else None,
                "job_id": job.job_id if job else None,
                "capability": required, "operation": command}
