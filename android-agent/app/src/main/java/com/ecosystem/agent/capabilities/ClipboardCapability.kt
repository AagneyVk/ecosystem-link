package com.ecosystem.agent.capabilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.ecosystem.agent.net.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ClipboardCapability(private val context: Context) : Capability {
    override val name = "clipboard.text"
    override val handledCommands = setOf("clipboard_set", "clipboard_get", "clipboard_clear")
    override fun isPermissionGranted() = true
    override fun permissionState() = "foreground_only"
    override fun provider() = "android.clipboard_manager"
    override fun restrictionReason() = "Android may block clipboard reads unless the app is visible. Writing remains available."
    override fun metadata() = buildJsonObject { put("maximum_bytes", 65536); put("automatic_sync", false) }
    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return when (command) {
            "clipboard_set" -> {
                val text = params["text"]?.jsonPrimitive?.content ?: ""
                if (text.toByteArray().size > 65536) CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Clipboard text exceeds 64 KiB")
                else { manager.setPrimaryClip(ClipData.newPlainText("Ecosystem", text)); CapabilityResult.Success(buildJsonObject { put("length", text.length) }) }
            }
            "clipboard_get" -> {
                val text = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text == null) CapabilityResult.Failure(ErrorCode.PERMISSION_DENIED, "Android did not expose clipboard text.", "Open Ecosystem Agent on the phone, then retry.", true)
                else CapabilityResult.Success(buildJsonObject { put("text", text.take(65536)); put("length", text.length) })
            }
            "clipboard_clear" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    manager.clearPrimaryClip()
                } else {
                    manager.setPrimaryClip(ClipData.newPlainText("Ecosystem", ""))
                }
                CapabilityResult.Success()
            }
            else -> CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Unsupported clipboard operation")
        }
    }
}
