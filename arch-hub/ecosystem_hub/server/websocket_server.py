"""
WebSocket control-plane server.

Handles connection lifecycle, handshake/capability registration, command
dispatch, session control acks, and state updates. Deliberately thin: all
business logic lives in the dispatcher/plugins/session manager so this
file stays stable as new capabilities are added.
"""

from __future__ import annotations

import asyncio
from collections import deque
import logging
from typing import Any

import websockets
from websockets.server import WebSocketServerProtocol

from ecosystem_hub.core.protocol import Envelope, MessageType, ErrorCode, error_payload
from ecosystem_hub.plugins.base import HubPluginError

logger = logging.getLogger("ecosystem_hub.ws")


class WebSocketHub:
    def __init__(self, *, dispatcher, capability_registry, device_manager, session_manager,
                 host: str = "0.0.0.0", port: int = 8765, shared_secret: str | None = None):
        self.dispatcher = dispatcher
        self.capability_registry = capability_registry
        self.device_manager = device_manager
        self.session_manager = session_manager
        self.host = host
        self.port = port
        self.shared_secret = shared_secret  # pre-shared token; VPN provides transport trust
        self._connections: dict[str, WebSocketServerProtocol] = {}
        self._server = None
        self._recent_message_ids: dict[str, deque[str]] = {}
        # Optional async callback: async fn(dict) -> None, wired to UIServer.broadcast
        self.on_device_event: Any = None

    async def start(self) -> None:
        self._server = await websockets.serve(self._handle_connection, self.host, self.port, max_size=8 * 1024 * 1024)
        logger.info("websocket hub listening on %s:%d", self.host, self.port)

    async def stop(self) -> None:
        if self._server:
            self._server.close()
            await self._server.wait_closed()

    async def send_to_device(self, device_id: str, envelope: Envelope) -> None:
        ws = self._connections.get(device_id)
        if not ws:
            logger.warning("attempted send to disconnected device %s", device_id)
            return
        envelope.device_id = device_id
        await ws.send(_dumps(envelope.to_dict()))

    async def _handle_connection(self, ws: WebSocketServerProtocol) -> None:
        device_id: str | None = None
        peer = ws.remote_address
        logger.info("incoming websocket connection from %s", peer)
        try:
            device_id = await self._await_handshake(ws)
            if device_id is None:
                return

            device = self.device_manager.mark_connected(device_id, ws)
            self._connections[device_id] = ws
            await self.dispatcher.broadcast_connect(device_id)
            if self.on_device_event:
                caps = self.capability_registry.get(device_id)
                await self.on_device_event({
                    "type": "device_connected",
                    "device": {
                        "device_id": device.device_id,
                        "display_name": device.display_name,
                        "connected": True,
                        "runtime_state": device.runtime_state.to_dict(),
                        "capabilities": caps.to_dict()["capabilities"] if caps else [],
                    },
                })

            async for raw in ws:
                await self._handle_message(device_id, raw)

        except websockets.ConnectionClosed as exc:
            logger.info("connection closed for device %s peer=%s code=%s reason=%s",
                        device_id, peer, exc.code, exc.reason)
        finally:
            if device_id:
                self._connections.pop(device_id, None)
                self.device_manager.mark_disconnected(device_id)
                await self.session_manager.fail_all_for_device(device_id)
                await self.dispatcher.broadcast_disconnect(device_id)
                if self.on_device_event:
                    await self.on_device_event({"type": "device_disconnected", "device_id": device_id})

    async def _await_handshake(self, ws: WebSocketServerProtocol) -> str | None:
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=15)
        except asyncio.TimeoutError:
            logger.warning("handshake timeout from %s", ws.remote_address)
            await ws.close(code=4000, reason="handshake timeout")
            return None

        env = Envelope.from_dict(_loads(raw))
        env.validate()
        if env.type != MessageType.HANDSHAKE.value:
            logger.warning("rejected %s: first message was %s", ws.remote_address, env.type)
            await ws.close(code=4001, reason="expected handshake")
            return None

        payload = env.payload
        if self.shared_secret and payload.get("secret") != self.shared_secret:
            logger.warning("authentication failed for %s", ws.remote_address)
            await ws.close(code=4003, reason="authentication failed")
            return None

        device_id = payload.get("device_id")
        if not device_id:
            logger.warning("rejected %s: missing device_id", ws.remote_address)
            await ws.close(code=4002, reason="missing device_id")
            return None

        caps = self.capability_registry.get_or_create(device_id)
        caps.update_from_list(payload.get("capabilities", []))

        device = self.device_manager.get_or_create(device_id, payload.get("display_name", ""))
        if "runtime_state" in payload:
            device.runtime_state.update(payload["runtime_state"])

        ack = Envelope(
            type=MessageType.HANDSHAKE_ACK.value,
            payload={"status": "ok", "server_time": env.timestamp},
            in_reply_to=env.msg_id,
        )
        await ws.send(_dumps(ack.to_dict()))
        logger.info("handshake complete for device %s (%d capabilities)", device_id, len(caps.all()))
        return device_id

    async def _handle_message(self, device_id: str, raw: str) -> None:
        try:
            env = Envelope.from_dict(_loads(raw))
            env.validate()
        except Exception:
            logger.exception("failed to parse message from %s", device_id)
            return

        recent = self._recent_message_ids.setdefault(device_id, deque(maxlen=1024))
        if env.msg_id in recent:
            logger.warning("ignored duplicate message %s from %s", env.msg_id, device_id)
            return
        recent.append(env.msg_id)

        if env.type == MessageType.PING.value:
            await self.send_to_device(device_id, Envelope(type=MessageType.PONG.value, payload={}, in_reply_to=env.msg_id))
            return

        if env.type == MessageType.STATE_UPDATE.value:
            device = self.device_manager.get(device_id)
            if device:
                device.runtime_state.update(env.payload)
            return

        if env.type == MessageType.SESSION_EVENT.value:
            await self._handle_session_event(device_id, env)
            return

        if env.type == MessageType.EVENT.value:
            if env.payload.get("event") == "sensor.sample":
                from ecosystem_hub.core.telemetry import validate_sensor_sample
                try:
                    env.payload = validate_sensor_sample(env.payload)
                except (ValueError, TypeError):
                    logger.warning("rejected malformed sensor sample from %s", device_id)
                    return
            if self.on_device_event:
                await self.on_device_event({"type": env.payload.get("event", "device.event"),
                                            "device_id": device_id, **env.payload})
            return

        if env.type in (MessageType.RESPONSE.value, MessageType.ERROR.value):
            if self.on_device_event:
                await self.on_device_event({
                    "type": "command_result" if env.type == MessageType.RESPONSE.value else "error",
                    "device_id": device_id, "correlation_id": env.correlation_id,
                    **env.payload,
                })
            return

        if env.type == MessageType.COMMAND.value:
            await self._handle_command(device_id, env)
            return

        logger.warning("unhandled message type '%s' from %s (forward-compat: ignoring)", env.type, device_id)

    async def _handle_command(self, device_id: str, env: Envelope) -> None:
        command = env.payload.get("command")
        params = env.payload.get("params", {})
        try:
            result = await self.dispatcher.dispatch(device_id, command, params)
            response = Envelope(type=MessageType.RESPONSE.value, payload={"command": command, "result": result},
                                 in_reply_to=env.msg_id)
        except HubPluginError as e:
            response = Envelope(type=MessageType.ERROR.value,
                                 payload=error_payload(ErrorCode(e.code) if e.code in ErrorCode._value2member_map_ else ErrorCode.INTERNAL_ERROR,
                                                        e.message, **{k: str(v) for k, v in e.extra.items()}),
                                 in_reply_to=env.msg_id)
        except Exception:
            logger.exception("unexpected error dispatching command %s for %s", command, device_id)
            response = Envelope(type=MessageType.ERROR.value,
                                 payload=error_payload(ErrorCode.INTERNAL_ERROR, "internal hub error"),
                                 in_reply_to=env.msg_id)

        await self.send_to_device(device_id, response)

    async def _handle_session_event(self, device_id: str, env: Envelope) -> None:
        """
        Agent-originated session lifecycle events, e.g. a stream that
        failed mid-flight due to an Android restriction, or a stream
        confirming it started successfully.
        """
        from ecosystem_hub.core.protocol import SessionState
        session_id = env.payload.get("session_id")
        state_str = env.payload.get("state")
        error = env.payload.get("error")
        if not session_id or not state_str:
            return
        try:
            new_state = SessionState(state_str)
        except ValueError:
            logger.warning("unknown session state '%s' from %s", state_str, device_id)
            return
        session = await self.session_manager.transition(session_id, new_state, error=error)
        if session and self.on_device_event:
            await self.on_device_event({"type": "session_updated", "session": session.to_dict()})


def _dumps(d: dict[str, Any]) -> str:
    import json
    return json.dumps(d)


def _loads(raw: str) -> dict[str, Any]:
    import json
    return json.loads(raw)
