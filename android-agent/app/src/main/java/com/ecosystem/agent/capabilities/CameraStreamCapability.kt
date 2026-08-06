package com.ecosystem.agent.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ecosystem.agent.net.CommandName
import com.ecosystem.agent.net.ErrorCode
import com.ecosystem.agent.service.ForegroundServiceController
import com.ecosystem.agent.service.CameraForegroundService
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "CameraStreamCapability"

/**
 * Handles live camera streaming sessions.
 *
 * When the hub sends camera_stream_start, this capability:
 *   1. Opens a WebSocket to the hub's streaming server (ws://hub:8769/source/device/session)
 *   2. Binds a CameraX ImageAnalysis use case that captures JPEG frames
 *   3. Sends each JPEG frame as a binary WebSocket message
 *   4. Continues until camera_stream_stop or disconnection
 *
 * The hub streaming server relays those frames to any browser WebSocket viewers —
 * this is pure MJPEG-over-WebSocket with zero codec negotiation.
 */
class CameraStreamCapability(
    private val context: Context,
    private val foregroundServiceController: ForegroundServiceController,
    private val streamingBaseUrl: String,   // e.g. "ws://192.168.x.x:8769"
    private val deviceId: String,
    private val foregroundGate: ForegroundGate,
) : StreamingCapability {

    override val name = "camera.stream"
    override val handledCommands = setOf(
        CommandName.CAMERA_STREAM_START,
        CommandName.CAMERA_STREAM_STOP,
    )

    private data class ActiveStream(
        val ws: WebSocket,
        val cameraProvider: ProcessCameraProvider,
        val scope: CoroutineScope,
    )

    private val activeStreams = ConcurrentHashMap<String, ActiveStream>()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient()

    override fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun metadata(): JsonObject = buildJsonObject {
        put("protocol", "mjpeg_websocket")
        put("format", "jpeg")
    }

    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult {
        return when (command) {
            CommandName.CAMERA_STREAM_START -> startSession(sessionId, params)
            CommandName.CAMERA_STREAM_STOP  -> stopSession(sessionId)
            else -> CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Unhandled command $command")
        }
    }

    override suspend fun startSession(sessionId: String, params: JsonObject): CapabilityResult {
        if (!isPermissionGranted()) {
            return CapabilityResult.Failure(
                errorCode = ErrorCode.PERMISSION_DENIED,
                message = "Camera permission not granted",
                requiresUserInteraction = true,
                missingPermission = Manifest.permission.CAMERA,
            )
        }

        if (!foregroundGate.isAppForegrounded()) {
            foregroundGate.requestForeground()
            val foregrounded = withTimeoutOrNull(15_000) {
                while (!foregroundGate.isAppForegrounded()) delay(100)
                true
            } ?: false
            if (!foregrounded) {
                return CapabilityResult.Failure(
                    ErrorCode.CAMERA_FOREGROUND_REQUIRED,
                    "Android requires the companion app to be visible when live camera access begins.",
                    "Open Ecosystem Agent on the phone, then press Start live again.",
                    true,
                )
            }
        }

        val serviceStarted = foregroundServiceController.startCameraForegroundService(sessionId)
        if (!serviceStarted) {
            return CapabilityResult.Failure(
                errorCode = ErrorCode.FOREGROUND_SERVICE_DENIED,
                message = "Android denied starting the camera foreground service.",
                requiresUserInteraction = true,
            )
        }

        return try {
            withTimeout(5_000) {
                while (!CameraForegroundService.isRunning) delay(25)
            }
            val wsUrl = "$streamingBaseUrl/source/$deviceId/$sessionId"
            val streamScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val readySignal = CompletableDeferred<Unit>()

            val ws = connectStreamingWs(wsUrl, sessionId, streamScope, readySignal)
            withTimeout(12_000) { readySignal.await() } // do not leave a permanent STARTING session

            val cameraProvider = withContext(Dispatchers.Main) {
                bindCamera(sessionId, ws)
            }

            activeStreams[sessionId] = ActiveStream(ws, cameraProvider, streamScope)
            Log.i(TAG, "stream started: session=$sessionId url=$wsUrl")
            CapabilityResult.Success()
        } catch (e: Exception) {
            foregroundServiceController.stopCameraForegroundService()
            Log.e(TAG, "failed to start stream", e)
            CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Failed to start stream: ${e.message}")
        }
    }

    override suspend fun stopSession(sessionId: String): CapabilityResult {
        val stream = activeStreams.remove(sessionId)
            ?: return CapabilityResult.Failure(ErrorCode.SESSION_NOT_FOUND, "No active stream for session $sessionId")

        return withContext(Dispatchers.Main) {
            try {
                stream.cameraProvider.unbindAll()
            } catch (_: Exception) {}
            stream.ws.close(1000, "session stopped")
            stream.scope.cancel()
            foregroundServiceController.stopCameraForegroundService()
            Log.i(TAG, "stream stopped: session=$sessionId")
            CapabilityResult.Success()
        }
    }

    private fun connectStreamingWs(
        wsUrl: String,
        sessionId: String,
        scope: CoroutineScope,
        readySignal: CompletableDeferred<Unit>,
    ): WebSocket {
        val request = Request.Builder().url(wsUrl).build()
        return httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "streaming WS open: session=$sessionId")
                readySignal.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "streaming WS failed: session=$sessionId", t)
                if (!readySignal.isCompleted) readySignal.completeExceptionally(t)
                scope.cancel()
                activeStreams.remove(sessionId)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "streaming WS closed: session=$sessionId code=$code")
                scope.cancel()
                activeStreams.remove(sessionId)
            }
        })
    }

    private suspend fun bindCamera(sessionId: String, ws: WebSocket): ProcessCameraProvider {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val provider = withContext(Dispatchers.IO) {
            providerFuture.get()
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                val bytes = imageProxyToJpeg(imageProxy)
                ws.send(okio.ByteString.of(*bytes))
            } catch (e: Exception) {
                Log.w(TAG, "frame encode error: ${e.message}")
            } finally {
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        // Context must implement LifecycleOwner to bind here; the caller ensures this.
        provider.unbindAll()
        provider.bindToLifecycle(context as LifecycleOwner, cameraSelector, imageAnalysis)
        Log.i(TAG, "camera bound for streaming: session=$sessionId")
        return provider
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray {
        // Camera planes often contain row padding and pixel strides. A raw
        // concatenation works on some emulators but produces corrupt/empty
        // JPEGs on Samsung camera2 devices.
        val width = imageProxy.width
        val height = imageProxy.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        val y = yPlane.buffer
        var outputIndex = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) nv21[outputIndex++] = y.get(rowStart + col * yPlane.pixelStride)
        }
        val u = uPlane.buffer
        val v = vPlane.buffer
        for (row in 0 until height / 2) {
            val uRow = row * uPlane.rowStride
            val vRow = row * vPlane.rowStride
            for (col in 0 until width / 2) {
                nv21[outputIndex++] = v.get(vRow + col * vPlane.pixelStride)
                nv21[outputIndex++] = u.get(uRow + col * uPlane.pixelStride)
            }
        }

        val yuvImage = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21,
            width, height, null,
        )
        val encoded = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, width, height),
            70, // quality: balanced for LAN streaming
            encoded,
        )
        return encoded.toByteArray()
    }
}
