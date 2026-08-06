package com.ecosystem.agent.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ecosystem.agent.config.AgentPreferences
import com.ecosystem.agent.transfer.FileTransferClient
import com.ecosystem.agent.service.EcosystemAgentService
import com.ecosystem.agent.net.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ScreenRecordingService : Service() {
    companion object {
        const val ACTION_START = "screen.start"
        const val ACTION_STOP = "screen.stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "screen_capture"
        private const val NOTIFICATION = 1201
        private const val MAX_DURATION_SECONDS = 3600
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId = ""
    private var recordingStarted = false
    private var finalised = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || recordingStarted) return START_NOT_STICKY

        startProjectionForeground()
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        sessionId = intent.getStringExtra(ScreenCaptureActivity.EXTRA_SESSION_ID)
            ?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val duration = intent.getIntExtra(ScreenCaptureActivity.EXTRA_DURATION, 30)
            .coerceIn(1, MAX_DURATION_SECONDS)
        val quality = intent.getStringExtra(ScreenCaptureActivity.EXTRA_QUALITY) ?: "balanced"

        runCatching {
            acquireWakeLock(duration)
            startRecording(intent.getIntExtra(EXTRA_RESULT_CODE, 0), resultData, quality)
            mainHandler.postDelayed({ stopSelf() }, duration * 1000L)
        }.onFailure {
            output?.delete()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjectionForeground() {
        val stopIntent = Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Screen recording active")
            .setContentText("Recording the display. Protected windows may appear blank.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }

    private fun acquireWakeLock(durationSeconds: Int) {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ecosystem:screen-recording")
            .also { it.acquire((durationSeconds + 30L) * 1000L) }
    }

    private fun startRecording(resultCode: Int, data: Intent, quality: String) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val scale = when (quality) {
            "high" -> 1f
            "data_saver" -> .5f
            else -> .7f
        }
        val width = ((metrics.widthPixels * scale).toInt() / 2).coerceAtLeast(2) * 2
        val height = ((metrics.heightPixels * scale).toInt() / 2).coerceAtLeast(2) * 2
        val bitRate = when (quality) {
            "high" -> 8_000_000
            "data_saver" -> 2_000_000
            else -> 4_000_000
        }

        output = File(cacheDir, "screen_${sessionId}.mp4")
        recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else legacyRecorder()
        recorder!!.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(bitRate)
            setOutputFile(output!!.absolutePath)
            prepare()
        }
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)
        display = projection!!.createVirtualDisplay(
            "EcosystemScreen", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder!!.surface, null, null
        )
        recorder!!.start()
        recordingStarted = true
        EcosystemAgentService.reportSessionState(sessionId, SessionState.RUNNING)
    }

    @Suppress("DEPRECATION")
    private fun legacyRecorder() = MediaRecorder()

    override fun onDestroy() {
        if (finalised) return super.onDestroy()
        finalised = true
        mainHandler.removeCallbacksAndMessages(null)
        if (recordingStarted) runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        recorder?.release()
        recorder = null
        display?.release()
        display = null
        projection?.stop()
        projection = null
        wakeLock?.takeIf { it.isHeld }?.release()

        val completed = output?.takeIf { recordingStarted && it.exists() && it.length() > 0 }
        if (completed == null) {
            output?.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            scope.cancel()
        } else {
            scope.launch {
                try {
                    val prefs = AgentPreferences(this@ScreenRecordingService)
                    val result = FileTransferClient(prefs.transferBaseUrl(), prefs.deviceId())
                        .upload(sessionId, "screen_recording", completed)
                    if (result.isSuccess) completed.delete()
                    EcosystemAgentService.reportSessionState(
                        sessionId,
                        if (result.isSuccess) SessionState.COMPLETED else SessionState.FAILED,
                        result.exceptionOrNull()?.message,
                    )
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    scope.cancel()
                }
            }
        }
        super.onDestroy()
    }
}
