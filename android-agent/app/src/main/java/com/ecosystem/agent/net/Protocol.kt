package com.ecosystem.agent.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/**
 * Wire protocol for the Ecosystem control channel. This must stay in sync
 * with `ecosystem_hub/core/protocol.py` on the hub side. Both are
 * envelope-based specifically so new message types can be introduced
 * without breaking older peers: unknown `type` values should be logged
 * and ignored rather than causing a crash (see EcosystemWebSocketClient).
 */

const val PROTO_VERSION = 1

object MessageType {
    const val HANDSHAKE = "handshake"
    const val HANDSHAKE_ACK = "handshake_ack"
    const val COMMAND = "command"
    const val RESPONSE = "response"
    const val EVENT = "event"
    const val SESSION_CONTROL = "session_control"
    const val SESSION_EVENT = "session_event"
    const val STATE_UPDATE = "state_update"
    const val ERROR = "error"
    const val PING = "ping"
    const val PONG = "pong"
}

object CommandName {
    const val GET_CAPABILITIES = "get_capabilities"
    const val GET_ACTIVE_SESSIONS = "get_active_sessions"
    const val GET_RUNTIME_STATE = "get_runtime_state"
    const val TAKE_PHOTO = "take_photo"
    const val RECORD_AUDIO = "record_audio"
    const val CAMERA_STREAM_START = "camera_stream_start"
    const val CAMERA_STREAM_STOP = "camera_stream_stop"
    const val MIC_STREAM_START = "microphone_stream_start"
    const val MIC_STREAM_STOP = "microphone_stream_stop"
    const val SENSOR_REFRESH = "sensor_refresh"
    const val SENSOR_STREAM_START = "sensor_stream_start"
    const val SENSOR_STREAM_STOP = "sensor_stream_stop"
    const val LOCATION_CURRENT = "location_current"
    const val LOCATION_STREAM_START = "location_stream_start"
    const val LOCATION_STREAM_STOP = "location_stream_stop"
    const val SCREEN_PREPARE = "screen_prepare"
    const val SCREEN_RECORD_START = "screen_record_start"
    const val SCREEN_RECORD_STOP = "screen_record_stop"
    const val SCREEN_STREAM_START = "screen_stream_start"
    const val SCREEN_STREAM_STOP = "screen_stream_stop"
}

object SessionState {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val STOPPING = "stopping"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/** Structured error codes - mirrors ErrorCode in the hub's protocol.py. */
object ErrorCode {
    const val CAPABILITY_NOT_FOUND = "CAPABILITY_NOT_FOUND"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val CAMERA_FOREGROUND_REQUIRED = "CAMERA_FOREGROUND_REQUIRED"
    const val FOREGROUND_SERVICE_DENIED = "FOREGROUND_SERVICE_DENIED"
    const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
    const val SESSION_ALREADY_ACTIVE = "SESSION_ALREADY_ACTIVE"
    const val DEVICE_OFFLINE = "DEVICE_OFFLINE"
    const val TRANSFER_FAILED = "TRANSFER_FAILED"
    const val CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

@Serializable
data class Envelope(
    val type: String,
    val payload: JsonObject,
    val msg_id: String = UUID.randomUUID().toString(),
    val proto_version: Int = PROTO_VERSION,
    val timestamp: String = Instant.now().toString(),
    val in_reply_to: String? = null,
    val device_id: String? = null,
    val correlation_id: String? = in_reply_to,
)

/** Structured restricted-capability failure, per the runtime-requirements doc. */
data class RestrictedCapabilityError(
    val error: String,
    val message: String,
    val recommendedAction: String,
    val requiresUserInteraction: Boolean = false,
    val missingPermission: String? = null,
)
