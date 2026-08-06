package com.ecosystem.agent.capabilities

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * A Capability is the Android-side counterpart to a hub plugin: one class
 * per capability category (camera, microphone, and future ones like
 * clipboard or notifications). New capabilities are added by implementing
 * this interface and registering an instance with [CapabilityRegistry] -
 * no changes to the WebSocket client or session machinery are required.
 */
interface Capability {
    /** e.g. "camera.snapshot" - matches the dotted name advertised at handshake. */
    val name: String

    /** Whether the underlying Android permission is currently granted. */
    fun isPermissionGranted(): Boolean

    /** Optional metadata advertised at handshake, e.g. supported resolutions. */
    fun metadata(): JsonObject = buildJsonObject {}

    fun isAvailable(): Boolean = true
    fun permissionState(): String = if (isPermissionGranted()) "granted" else "denied"
    fun restrictionReason(): String? = null
    fun provider(): String? = null

    /** Which commands (see CommandName) this capability handles. */
    val handledCommands: Set<String>

    /**
     * Execute a one-shot command (e.g. take_photo). Long-running operations
     * should instead be modeled as a Session - see [capabilities.StreamingCapability].
     */
    suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult
}

/** Capabilities that support session-based start/stop (streams). */
interface StreamingCapability : Capability {
    suspend fun startSession(sessionId: String, params: JsonObject): CapabilityResult
    suspend fun stopSession(sessionId: String): CapabilityResult
}

sealed class CapabilityResult {
    data class Success(val data: JsonObject = buildJsonObject {}) : CapabilityResult()
    data class Failure(
        val errorCode: String,
        val message: String,
        val recommendedAction: String = "",
        val requiresUserInteraction: Boolean = false,
        val missingPermission: String? = null,
    ) : CapabilityResult()
}

class CapabilityRegistry {
    private val capabilities = mutableMapOf<String, Capability>()
    private val commandOwners = mutableMapOf<String, Capability>()
    /** Maps session_type strings (e.g. "camera_stream") to their StreamingCapability. */
    private val sessionTypeOwners = mutableMapOf<String, StreamingCapability>()

    fun register(capability: Capability) {
        capabilities[capability.name] = capability
        capability.handledCommands.forEach { commandOwners[it] = capability }
    }

    fun registerStreamingCapability(sessionType: String, capability: StreamingCapability) {
        register(capability)
        sessionTypeOwners[sessionType] = capability
    }

    fun ownerOf(command: String): Capability? = commandOwners[command]

    /** Returns the StreamingCapability that handles the given session type, or null. */
    fun streamingCapabilityFor(sessionType: String): StreamingCapability? =
        sessionTypeOwners[sessionType]

    fun all(): List<Capability> = capabilities.values.toList()

    /** Builds the handshake payload's capability list. */
    fun toHandshakeList(): List<Map<String, Any>> = capabilities.values.map {
        mapOf(
            "name" to it.name,
            "permission_granted" to it.isPermissionGranted(),
            "metadata" to it.metadata(),
        )
    }
}
