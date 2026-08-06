package com.ecosystem.agent.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Build
import androidx.core.content.ContextCompat
import com.ecosystem.agent.net.CommandName
import com.ecosystem.agent.net.ErrorCode
import com.ecosystem.agent.service.ForegroundServiceController
import com.ecosystem.agent.session.SessionManager
import com.ecosystem.agent.transfer.FileTransferClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * microphone.record (one-shot, bounded duration) and microphone.stream
 * (open-ended session start/stop). Per the runtime requirements doc,
 * microphone capture must run inside a foreground service - this
 * capability doesn't start that service itself, it asks
 * [ForegroundServiceController] to do so and reports FOREGROUND_SERVICE_DENIED
 * structurally if Android refuses (e.g. missing FOREGROUND_SERVICE_MICROPHONE
 * permission on API 34+, or battery-optimization restrictions).
 */
class MicrophoneCapability(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val transferClient: FileTransferClient,
    private val foregroundServiceController: ForegroundServiceController,
    private val streamingBaseUrl: String,
    private val deviceId: String,
) : StreamingCapability {

    override val name = "microphone.record"
    override val handledCommands = setOf(
        CommandName.RECORD_AUDIO,
        CommandName.MIC_STREAM_START,
        CommandName.MIC_STREAM_STOP,
    )

    private val activeRecorders = ConcurrentHashMap<String, ActiveRecording>()
    private val activeStreams = ConcurrentHashMap<String, ActiveAudioStream>()
    private val httpClient = OkHttpClient()

    private data class ActiveRecording(val recorder: MediaRecorder, val outputFile: File)
    private data class ActiveAudioStream(
        val recorder: AudioRecord,
        val socket: WebSocket,
        val scope: CoroutineScope,
        val job: Job,
    )

    override fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun metadata(): JsonObject = buildJsonObject {
        put("format", "m4a")
        put("sample_rate_hz", 44100)
        put("live_format", "pcm_s16le")
        put("live_sample_rate_hz", 16000)
        put("live_channels", 1)
    }

    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult {
        if (!isPermissionGranted()) {
            return CapabilityResult.Failure(
                errorCode = ErrorCode.PERMISSION_DENIED,
                message = "Microphone permission is not granted.",
                recommendedAction = "Grant microphone permission in Android settings.",
                requiresUserInteraction = true,
                missingPermission = Manifest.permission.RECORD_AUDIO,
            )
        }

        return when (command) {
            CommandName.RECORD_AUDIO -> recordBounded(sessionId, params)
            CommandName.MIC_STREAM_START -> startSession(sessionId, params)
            CommandName.MIC_STREAM_STOP -> stopSession(sessionId)
            else -> CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Unhandled command $command")
        }
    }

    override suspend fun startSession(sessionId: String, params: JsonObject): CapabilityResult {
        val serviceStarted = foregroundServiceController.startMicrophoneForegroundService(sessionId)
        if (!serviceStarted) {
            return CapabilityResult.Failure(
                errorCode = ErrorCode.FOREGROUND_SERVICE_DENIED,
                message = "Android denied starting the microphone foreground service.",
                recommendedAction = "Check notification permission and battery optimization settings for this app.",
                requiresUserInteraction = true,
            )
        }

        return try {
            val ready = CompletableDeferred<Unit>()
            val socket = httpClient.newWebSocket(
                Request.Builder().url("$streamingBaseUrl/source/$deviceId/$sessionId").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!ready.isCompleted) ready.completeExceptionally(t)
                    }
                },
            )
            withTimeout(12_000) { ready.await() }
            val sampleRate = 16_000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)
            val audioRecord = AudioRecord(
                AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2,
            )
            audioRecord.startRecording()
            val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val job = streamScope.launch {
                val buffer = ByteArray(minBuffer)
                while (true) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count > 0 && !socket.send(buffer.copyOf(count).toByteString())) break
                }
            }
            activeStreams[sessionId] = ActiveAudioStream(audioRecord, socket, streamScope, job)
            CapabilityResult.Success(buildJsonObject { put("format", "pcm_s16le"); put("sample_rate_hz", sampleRate) })
        } catch (e: Exception) {
            foregroundServiceController.stopMicrophoneForegroundService()
            CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Failed to start recorder: ${e.message}")
        }
    }

    override suspend fun stopSession(sessionId: String): CapabilityResult = withContext(Dispatchers.IO) {
        val active = activeStreams.remove(sessionId)
            ?: return@withContext CapabilityResult.Failure(ErrorCode.SESSION_NOT_FOUND, "No active audio stream for session $sessionId")
        active.job.cancel()
        runCatching { active.recorder.stop() }
        active.recorder.release()
        active.socket.close(1000, "session stopped")
        active.scope.cancel()
        foregroundServiceController.stopMicrophoneForegroundService()
        CapabilityResult.Success()
    }

    private suspend fun recordBounded(sessionId: String, params: JsonObject): CapabilityResult {
        val durationMs = when {
            params["duration_ms"] != null -> params["duration_ms"]!!.jsonPrimitive.content.toLongOrNull() ?: 10_000L
            params["duration_seconds"] != null -> (params["duration_seconds"]!!.jsonPrimitive.content.toLongOrNull() ?: 10L) * 1000L
            else -> 10_000L
        }.coerceIn(1_000L, 600_000L)

        val serviceStarted = foregroundServiceController.startMicrophoneForegroundService(sessionId)
        if (!serviceStarted) {
            return CapabilityResult.Failure(
                errorCode = ErrorCode.FOREGROUND_SERVICE_DENIED,
                message = "Android denied starting the microphone foreground service.",
                recommendedAction = "Check notification permission and battery optimization settings for this app.",
                requiresUserInteraction = true,
            )
        }

        return try {
            val recording = startRecorder(sessionId)
            kotlinx.coroutines.delay(durationMs)
            recording.recorder.stop()
            recording.recorder.release()
            foregroundServiceController.stopMicrophoneForegroundService()

            val uploadResult = transferClient.upload(sessionId, "microphone_record", recording.outputFile)
            if (uploadResult.isSuccess) {
                recording.outputFile.delete()
                CapabilityResult.Success()
            } else {
                CapabilityResult.Failure(ErrorCode.TRANSFER_FAILED, uploadResult.exceptionOrNull()?.message ?: "upload failed")
            }
        } catch (e: Exception) {
            foregroundServiceController.stopMicrophoneForegroundService()
            CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Recording failed: ${e.message}")
        }
    }

    private fun startRecorder(sessionId: String): ActiveRecording {
        val outputFile = File(context.cacheDir, "mic_${sessionId}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        return ActiveRecording(recorder, outputFile)
    }
}
