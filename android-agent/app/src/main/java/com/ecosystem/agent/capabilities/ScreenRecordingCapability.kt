package com.ecosystem.agent.capabilities

import android.content.Context
import android.content.Intent
import com.ecosystem.agent.net.CommandName
import com.ecosystem.agent.net.ErrorCode
import com.ecosystem.agent.screen.ScreenCaptureActivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ScreenRecordingCapability(private val context: Context) : Capability {
    override val name = "screen.record"
    override val handledCommands = setOf(CommandName.SCREEN_PREPARE, CommandName.SCREEN_RECORD_START, CommandName.SCREEN_RECORD_STOP, CommandName.SCREEN_STREAM_START, CommandName.SCREEN_STREAM_STOP)
    override fun isPermissionGranted() = false // MediaProjection is consent, never a durable permission.
    override fun permissionState() = "consent_required"
    override fun provider() = "android.media_projection"
    override fun restrictionReason() = "Android requires confirmation on the phone for every recording session; secure/DRM windows remain unavailable."
    override fun metadata() = buildJsonObject {
        put("per_session_consent", true)
        put("protected_content_unavailable", true)
        put("full_display_supported", true)
        put("maximum_duration_seconds", 3600)
        put("internal_audio_supported", false)
    }
    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult = when (command) {
        CommandName.SCREEN_PREPARE, CommandName.SCREEN_RECORD_START, CommandName.SCREEN_STREAM_START -> {
            context.startActivity(Intent(context, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ScreenCaptureActivity.EXTRA_SESSION_ID, sessionId.ifBlank { java.util.UUID.randomUUID().toString() })
                putExtra(ScreenCaptureActivity.EXTRA_DURATION, (params["duration_seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30).coerceIn(1, 3600))
                putExtra(ScreenCaptureActivity.EXTRA_QUALITY, params["quality"]?.jsonPrimitive?.content ?: "balanced")
                putExtra(ScreenCaptureActivity.EXTRA_MODE, if (command == CommandName.SCREEN_STREAM_START) "live" else "record")
            })
            CapabilityResult.Success(buildJsonObject {
                put("status", "awaiting_user_consent")
                put("message", "Approve the Android system capture dialog on the phone.")
            })
        }
        CommandName.SCREEN_RECORD_STOP, CommandName.SCREEN_STREAM_STOP -> {
            context.stopService(Intent(context, com.ecosystem.agent.screen.ScreenRecordingService::class.java))
            context.stopService(Intent(context, com.ecosystem.agent.screen.ScreenLiveService::class.java))
            CapabilityResult.Success()
        }
        else -> CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Unsupported screen operation")
    }
}
