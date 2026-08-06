package com.ecosystem.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Starts/stops the dedicated microphone foreground service required by
 * Android's background execution model for audio capture. Kept as its own
 * component (rather than folded into MicrophoneCapability) so future
 * capabilities needing their own foreground service type (e.g. a future
 * camera-streaming capability) can reuse the same pattern without
 * duplicating notification-channel boilerplate.
 */
interface ForegroundServiceController {
    fun startMicrophoneForegroundService(sessionId: String): Boolean
    fun stopMicrophoneForegroundService()
    /** Required for camera streaming on API 29+ — FOREGROUND_SERVICE_TYPE_CAMERA. */
    fun startCameraForegroundService(sessionId: String): Boolean
    fun stopCameraForegroundService()
}

class DefaultForegroundServiceController(private val context: Context) : ForegroundServiceController {

    override fun startMicrophoneForegroundService(sessionId: String): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val granted = ContextCompat.checkSelfPermission(
                context, "android.permission.FOREGROUND_SERVICE_MICROPHONE"
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return try {
            val intent = Intent(context, MicrophoneForegroundService::class.java).apply {
                putExtra(MicrophoneForegroundService.EXTRA_SESSION_ID, sessionId)
            }
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) { false }
    }

    override fun stopMicrophoneForegroundService() {
        context.stopService(Intent(context, MicrophoneForegroundService::class.java))
    }

    override fun startCameraForegroundService(sessionId: String): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val granted = ContextCompat.checkSelfPermission(
                context, "android.permission.FOREGROUND_SERVICE_CAMERA"
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return try {
            val intent = Intent(context, CameraForegroundService::class.java).apply {
                putExtra(CameraForegroundService.EXTRA_SESSION_ID, sessionId)
            }
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) { false }
    }

    override fun stopCameraForegroundService() {
        context.stopService(Intent(context, CameraForegroundService::class.java))
    }
}

/**
 * Minimal foreground service whose sole job is to keep the process alive
 * and visible-to-the-user (via a persistent notification, per Android
 * policy) while MicrophoneCapability records. The actual MediaRecorder
 * lives in MicrophoneCapability / the main EcosystemAgentService - this
 * service exists purely to satisfy the foreground-service requirement
 * around microphone access.
 */
class MicrophoneForegroundService : android.app.Service() {
    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val CHANNEL_ID = "ecosystem_microphone"
        const val NOTIFICATION_ID = 1002
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannelIfNeeded()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ecosystem Agent")
            .setContentText("Recording audio for Linux hub session")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Microphone Capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}

/**
 * Foreground service that keeps the process alive during camera streaming.
 * Required by Android to use FOREGROUND_SERVICE_TYPE_CAMERA in a background context.
 */
class CameraForegroundService : android.app.Service() {
    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val CHANNEL_ID = "ecosystem_camera_stream"
        const val NOTIFICATION_ID = 1003
        @Volatile var isRunning: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannelIfNeeded()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ecosystem Agent")
            .setContentText("Streaming camera to Linux hub")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Camera Stream", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
