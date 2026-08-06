"""
Ecosystem Protocol v1
======================

Defines the wire format for all communication between the Linux hub and
Android agents. This is intentionally versioned and envelope-based so that
future message types (clipboard, notifications, presence, etc.) can be
added without breaking existing clients.

Every message on the wire is a single JSON object with this envelope:

{
    "proto_version": 1,
    "msg_id": "uuid4 string",           # unique per message, used for correlation
    "type": "command" | "response" | "event" | "session_control" | "handshake",
    "timestamp": "ISO8601 UTC",
    "payload": { ... type-specific ... }
}

Responses correlate to requests via "in_reply_to": "<msg_id of request>".

Design notes for future extensibility:
    - New capability types are just new strings in the capability registry;
      no protocol change is required to add e.g. "clipboard.read".
    - New message "type" values can be added; unknown types should be
      logged and ignored by both sides rather than causing a crash, to
      preserve backward/forward compatibility across agent/hub versions.
    - payload schemas are versioned implicitly by capability name
      (e.g. "camera.snapshot.v1") if a breaking change is ever needed.
"""

from __future__ import annotations

import uuid
import enum
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Optional

PROTO_VERSION = 1
SUPPORTED_PROTOCOL_VERSIONS = frozenset({1})


class MessageType(str, enum.Enum):
    HANDSHAKE = "handshake"
    HANDSHAKE_ACK = "handshake_ack"
    COMMAND = "command"
    RESPONSE = "response"
    EVENT = "event"
    SESSION_CONTROL = "session_control"
    SESSION_EVENT = "session_event"
    STATE_UPDATE = "state_update"
    ERROR = "error"
    PING = "ping"
    PONG = "pong"


class CommandName(str, enum.Enum):
    GET_CAPABILITIES = "get_capabilities"
    GET_ACTIVE_SESSIONS = "get_active_sessions"
    GET_RUNTIME_STATE = "get_runtime_state"
    TAKE_PHOTO = "take_photo"
    RECORD_AUDIO = "record_audio"
    CAMERA_STREAM_START = "camera_stream_start"
    CAMERA_STREAM_STOP = "camera_stream_stop"
    MIC_STREAM_START = "microphone_stream_start"
    MIC_STREAM_STOP = "microphone_stream_stop"
    CANCEL_SESSION = "cancel_session"
    SCREEN_STREAM_START = "screen_stream_start"
    SCREEN_STREAM_STOP = "screen_stream_stop"


class SessionControlAction(str, enum.Enum):
    START = "start"
    STOP = "stop"
    STATUS = "status"


class SessionState(str, enum.Enum):
    PENDING = "pending"
    RUNNING = "running"
    STOPPING = "stopping"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


def new_msg_id() -> str:
    return str(uuid.uuid4())


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class Envelope:
    type: str
    payload: dict[str, Any]
    msg_id: str = field(default_factory=new_msg_id)
    proto_version: int = PROTO_VERSION
    timestamp: str = field(default_factory=now_iso)
    in_reply_to: Optional[str] = None
    device_id: Optional[str] = None
    correlation_id: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        d = {
            "proto_version": self.proto_version,
            "msg_id": self.msg_id,
            "type": self.type,
            "timestamp": self.timestamp,
            "payload": self.payload,
        }
        if self.in_reply_to:
            d["in_reply_to"] = self.in_reply_to
        if self.device_id:
            d["device_id"] = self.device_id
        if self.correlation_id:
            d["correlation_id"] = self.correlation_id
        return d

    @staticmethod
    def from_dict(d: dict[str, Any]) -> "Envelope":
        return Envelope(
            type=d.get("type", ""),
            payload=d.get("payload", {}) or {},
            msg_id=d.get("msg_id", new_msg_id()),
            proto_version=d.get("proto_version", PROTO_VERSION),
            timestamp=d.get("timestamp", now_iso()),
            in_reply_to=d.get("in_reply_to"),
            device_id=d.get("device_id"),
            correlation_id=d.get("correlation_id") or d.get("in_reply_to"),
        )

    def validate(self) -> None:
        if self.proto_version not in SUPPORTED_PROTOCOL_VERSIONS:
            raise ValueError(f"unsupported protocol version: {self.proto_version}")
        if not self.msg_id or not isinstance(self.payload, dict) or not self.type:
            raise ValueError("invalid protocol envelope")


# --- Structured error codes -------------------------------------------------
# Mirrors the restricted-capability error contract from the Android side so
# the hub can branch on failure reason (e.g. surface "needs foreground" to
# a UI, or auto-retry on transient network errors).

class ErrorCode(str, enum.Enum):
    CAPABILITY_NOT_FOUND = "CAPABILITY_NOT_FOUND"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    CAMERA_FOREGROUND_REQUIRED = "CAMERA_FOREGROUND_REQUIRED"
    FOREGROUND_SERVICE_DENIED = "FOREGROUND_SERVICE_DENIED"
    SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
    SESSION_ALREADY_ACTIVE = "SESSION_ALREADY_ACTIVE"
    DEVICE_OFFLINE = "DEVICE_OFFLINE"
    TRANSFER_FAILED = "TRANSFER_FAILED"
    CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
    INTERNAL_ERROR = "INTERNAL_ERROR"


def error_payload(code: ErrorCode, message: str, *, recommended_action: str = "",
                   requires_user_interaction: bool = False, missing_permission: str = "") -> dict:
    return {
        "error": code.value,
        "message": message,
        "recommended_action": recommended_action,
        "requires_user_interaction": requires_user_interaction,
        "missing_permission": missing_permission,
    }
