package com.ecosystem.agent.net

import android.util.Log
import com.ecosystem.agent.capabilities.CapabilityRegistry
import com.ecosystem.agent.capabilities.CapabilityResult
import com.ecosystem.agent.session.SessionManager
import com.ecosystem.agent.state.RuntimeStateReporter
import com.ecosystem.agent.state.ConnectionStatusHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val TAG = "EcosystemWS"

/**
 * Owns the single persistent WebSocket connection to the hub.
 */
class EcosystemWebSocketClient(
    private val hubUrl: String,
    private val deviceId: String,
    private val displayName: String,
    private val capabilityRegistry: CapabilityRegistry,
    private val sessionManager: SessionManager,
    private val runtimeStateReporter: RuntimeStateReporter,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var handshakeTimeoutJob: Job? = null
    @Volatile private var handshakeComplete = false
    private var shouldRun = false
    private var reconnectAttempt = 0

    var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null

    fun start() {
        Log.i(TAG, "WebSocket client starting: hubUrl=$hubUrl, deviceId=$deviceId")
        ConnectionStatusHolder.setStatus("Connecting to $hubUrl...")
        shouldRun = true
        connect()
    }

    fun stop() {
        shouldRun = false
        reconnectJob?.cancel()
        handshakeTimeoutJob?.cancel()
        webSocket?.close(1000, "client shutdown")
        webSocket = null
    }

    fun isConnected(): Boolean = webSocket != null && handshakeComplete

    fun sendStateUpdate(payload: JsonObject) {
        send(Envelope(type = MessageType.STATE_UPDATE, payload = payload))
    }

    fun sendSessionEvent(sessionId: String, state: String, error: String? = null) {
        val payload = buildJsonObject {
            put("session_id", sessionId)
            put("state", state)
            if (error != null) put("error", error)
        }
        send(Envelope(type = MessageType.SESSION_EVENT, payload = payload))
    }

    fun sendEvent(payload: JsonObject) {
        send(Envelope(type = MessageType.EVENT, payload = payload, device_id = deviceId))
    }

    private fun connect() {
        if (hubUrl.isBlank()) {
            Log.e(TAG, "Hub URL is blank, cannot connect")
            return
        }
        Log.i(TAG, "Attempting to connect to WebSocket at $hubUrl")
        ConnectionStatusHolder.addLog("DEBUG", "Connecting to $hubUrl")
        try {
            val request = Request.Builder().url(hubUrl).build()
            webSocket = client.newWebSocket(request, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate connection to $hubUrl: ${e.message}", e)
            ConnectionStatusHolder.setError("Connection failed: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempt++
            val backoffMs = minOf(30_000L, 1000L * (1 shl minOf(reconnectAttempt, 5)))
            Log.i(TAG, "reconnecting in ${backoffMs}ms (attempt $reconnectAttempt)")
            ConnectionStatusHolder.addLog("WARNING", "Retrying in ${backoffMs/1000}s (attempt $reconnectAttempt)")
            delay(backoffMs)
            if (shouldRun) connect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "[HANDSHAKE] Connected to hub at $hubUrl")
            handshakeComplete = false
            ConnectionStatusHolder.addLog("INFO", "WebSocket upgraded (HTTP ${response.code}); sending protocol handshake")
            reconnectAttempt = 0
            val handshake = Envelope(
                type = MessageType.HANDSHAKE,
                payload = buildJsonObject {
                    put("device_id", deviceId)
                    put("display_name", displayName)
                    put("capabilities", buildCapabilitiesJson())
                    put("runtime_state", runtimeStateReporter.currentStateAsJson())
                },
            )
            webSocket.send(json.encodeToString(handshake))
            handshakeTimeoutJob?.cancel()
            handshakeTimeoutJob = scope.launch {
                delay(12_000)
                if (!handshakeComplete) {
                    ConnectionStatusHolder.setError("WebSocket opened but the hub did not acknowledge the handshake within 12s")
                    webSocket.close(4000, "client handshake timeout")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleIncoming(text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handshakeTimeoutJob?.cancel()
            Log.w(TAG, "Connection closed by hub: code=$code reason=$reason endpoint=$hubUrl")
            ConnectionStatusHolder.setError("Hub closed connection ($code): ${reason.ifBlank { "no reason" }}")
            this@EcosystemWebSocketClient.webSocket = null
            handshakeComplete = false
            onConnectionStateChanged?.invoke(false)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handshakeTimeoutJob?.cancel()
            Log.w(TAG, "websocket failure at $hubUrl: ${t.message}", t)
            val diagnosis = when (t) {
                is UnknownHostException -> "DNS/name resolution failed"
                is ConnectException -> "TCP connection refused or VPN route unavailable"
                is SocketTimeoutException -> "TCP/WebSocket connection timed out"
                is SSLException -> "TLS negotiation failed"
                else -> t.javaClass.simpleName
            }
            val http = response?.let { " (HTTP ${it.code})" } ?: ""
            ConnectionStatusHolder.setError("$diagnosis$http at $hubUrl: ${t.message ?: "no detail"}")
            this@EcosystemWebSocketClient.webSocket = null
            handshakeComplete = false
            onConnectionStateChanged?.invoke(false)
            scheduleReconnect()
        }
    }

    private suspend fun handleIncoming(text: String) {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse incoming message: ${e.message}")
            return
        }

        when (envelope.type) {
            MessageType.HANDSHAKE_ACK -> {
                Log.i(TAG, "handshake acknowledged")
                handshakeComplete = true
                handshakeTimeoutJob?.cancel()
                ConnectionStatusHolder.addLog("INFO", "Protocol handshake acknowledged by hub")
                ConnectionStatusHolder.setConnected(true)
                onConnectionStateChanged?.invoke(true)
            }
            MessageType.COMMAND -> handleCommand(envelope)
            MessageType.SESSION_CONTROL -> handleSessionControl(envelope)
            MessageType.PING -> send(Envelope(type = MessageType.PONG, payload = buildJsonObject {}, in_reply_to = envelope.msg_id))
            else -> Log.d(TAG, "ignoring unhandled message type '${envelope.type}'")
        }
    }

    private suspend fun handleCommand(envelope: Envelope) {
        val command = envelope.payload["command"]?.jsonPrimitive?.content ?: return
        val params = envelope.payload["params"]?.jsonObject ?: buildJsonObject {}
        val sessionId = envelope.payload["session_id"]?.jsonPrimitive?.content ?: ""
        val capability = capabilityRegistry.ownerOf(command) ?: return
        
        when (val result = capability.handleCommand(command, params, sessionId)) {
            is CapabilityResult.Success -> {
                send(Envelope(type = MessageType.RESPONSE, payload = buildJsonObject {
                    put("command", command)
                    put("result", result.data)
                }, in_reply_to = envelope.msg_id))
            }
            is CapabilityResult.Failure -> {
                send(Envelope(type = MessageType.ERROR, payload = buildJsonObject {
                    put("error", result.errorCode)
                    put("message", result.message)
                    put("operation", command)
                    put("recommended_action", result.recommendedAction)
                    put("retryable", !result.requiresUserInteraction)
                    put("requires_user_interaction", result.requiresUserInteraction)
                    result.missingPermission?.let { put("missing_permission", it) }
                }, in_reply_to = envelope.msg_id))
            }
        }
    }

    private suspend fun handleSessionControl(envelope: Envelope) {
        val action = envelope.payload["action"]?.jsonPrimitive?.content ?: return
        val sessionType = envelope.payload["session_type"]?.jsonPrimitive?.content
        val sessionId = envelope.payload["session_id"]?.jsonPrimitive?.content ?: return
        val params = envelope.payload["params"]?.jsonObject ?: buildJsonObject {}

        when (action) {
            "start" -> {
                sessionManager.create(sessionId, sessionType ?: "unknown")
                // Resolve the capability that owns this session type and actually start it.
                // The registry is keyed by capability name, but we need the one that handles
                // the relevant session. We resolve by looking for the capability that owns
                // the stream-start command for this session type.
                val streamCapability = capabilityRegistry.streamingCapabilityFor(sessionType ?: "")
                if (streamCapability != null) {
                    val result = streamCapability.startSession(sessionId, params)
                    when (result) {
                        is com.ecosystem.agent.capabilities.CapabilityResult.Success -> {
                            sessionManager.transition(sessionId, SessionState.RUNNING)
                            sendSessionEvent(sessionId, SessionState.RUNNING)
                        }
                        is com.ecosystem.agent.capabilities.CapabilityResult.Failure -> {
                            sessionManager.transition(sessionId, SessionState.FAILED, result.message)
                            sendSessionEvent(sessionId, SessionState.FAILED, result.message)
                        }
                    }
                } else {
                    // No streaming capability registered for this session type — still
                    // acknowledge so the hub session lifecycle isn't orphaned.
                    sessionManager.transition(sessionId, SessionState.RUNNING)
                    sendSessionEvent(sessionId, SessionState.RUNNING)
                }
            }
            "stop" -> {
                val streamCapability = capabilityRegistry.streamingCapabilityFor(sessionType ?: "")
                streamCapability?.stopSession(sessionId)
                sessionManager.transition(sessionId, SessionState.COMPLETED)
                sendSessionEvent(sessionId, SessionState.COMPLETED)
            }
            else -> Log.w(TAG, "unknown session control action: $action")
        }
    }

    private fun send(envelope: Envelope) {
        webSocket?.send(json.encodeToString(envelope))
    }

    private fun buildCapabilitiesJson(): kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.JsonArray(
            capabilityRegistry.all().map { cap ->
                buildJsonObject {
                    put("name", cap.name)
                    put("capability_id", cap.name)
                    put("available", cap.isAvailable())
                    put("operations", kotlinx.serialization.json.JsonArray(cap.handledCommands.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    put("permission_granted", cap.isPermissionGranted())
                    put("permission_state", cap.permissionState())
                    cap.restrictionReason()?.let { put("restriction_reason", it) }
                    cap.provider()?.let { put("provider", it) }
                    put("metadata", cap.metadata())
                }
            }
        )
}
