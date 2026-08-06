package com.ecosystem.agent.capabilities

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ecosystem.agent.net.CommandName
import com.ecosystem.agent.net.ErrorCode
import com.ecosystem.agent.service.EcosystemAgentService
import com.ecosystem.agent.session.SessionManager
import com.ecosystem.agent.state.ConnectionStatusHolder
import com.ecosystem.agent.transfer.FileTransferClient
import com.ecosystem.agent.ui.CompanionActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "CameraCapability"

/**
 * camera.snapshot and camera.stream (stream is a stub for V1).
 */
class CameraCapability(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val transferClient: FileTransferClient,
    private val foregroundGate: ForegroundGate,
) : Capability {

    override val name = "camera.snapshot"
    override val handledCommands = setOf(CommandName.TAKE_PHOTO, CommandName.CAMERA_STREAM_START, CommandName.CAMERA_STREAM_STOP)

    override fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun metadata(): JsonObject = buildJsonObject {
        put("supports_streaming", false)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = manager.cameraIdList.mapNotNull { id -> when (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"; CameraCharacteristics.LENS_FACING_BACK -> "rear"; else -> null } }.distinct()
        put("lenses", kotlinx.serialization.json.JsonArray(lenses.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("jpeg_quality", true)
    }

    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult {
        Log.i(TAG, "[Camera] Received command=$command sessionId=$sessionId params=$params")
        if (!isPermissionGranted()) {
            return CapabilityResult.Failure(
                errorCode = ErrorCode.PERMISSION_DENIED,
                message = "Camera permission is not granted.",
                recommendedAction = "Grant camera permission in Android settings.",
                requiresUserInteraction = true,
                missingPermission = Manifest.permission.CAMERA,
            )
        }

        return when (command) {
            CommandName.TAKE_PHOTO -> takePhoto(sessionId, params)
            CommandName.CAMERA_STREAM_START -> CapabilityResult.Failure(
                errorCode = ErrorCode.CAPABILITY_NOT_FOUND,
                message = "Camera streaming is not implemented in Version 1.",
                recommendedAction = "Use take_photo for periodic snapshots instead.",
            )
            CommandName.CAMERA_STREAM_STOP -> CapabilityResult.Success()
            else -> CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Unhandled command $command")
        }
    }

    private suspend fun takePhoto(sessionId: String, params: JsonObject): CapabilityResult {
        if (!foregroundGate.isAppForegrounded()) {
            // Check for notification permission on API 33+ before attempting to wake the device.
            // If missing, we can't auto-wake, so we report a structured failure.
            if (Build.VERSION.SDK_INT >= 33 && 
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                
                Log.e(TAG, "[Camera] Notification permission missing, cannot wake phone for session=$sessionId")
                return CapabilityResult.Failure(
                    errorCode = ErrorCode.PERMISSION_DENIED,
                    message = "Notification permission is required to wake the device for camera access while locked.",
                    recommendedAction = "Grant notification permission to the Ecosystem Agent app.",
                    requiresUserInteraction = true,
                    missingPermission = Manifest.permission.POST_NOTIFICATIONS
                )
            }

            Log.w(TAG, "[Camera] App is not foregrounded; requesting foreground via full-screen intent for session=$sessionId")
            ConnectionStatusHolder.addLog("WARNING", "Waking phone for camera session $sessionId")
            foregroundGate.requestForeground()

            val becameForeground = waitForForeground(sessionId)
            if (!becameForeground) {
                return CapabilityResult.Failure(
                    errorCode = ErrorCode.CAMERA_FOREGROUND_REQUIRED,
                    message = "Android did not bring the companion application to the foreground. The phone may be locked or permission for full-screen intents may be disabled.",
                    recommendedAction = "Wake/unlock the phone or tap the heads-up notification manually.",
                    requiresUserInteraction = true,
                )
            }
        }

        val outputFile = File(context.cacheDir, "camera_${sessionId}.jpg")
        Log.i(TAG, "[Camera] Capturing photo for session=$sessionId -> ${outputFile.absolutePath}")
        ConnectionStatusHolder.setStatus("Capturing photo for session $sessionId")

        return try {
            val lens = params["lens"]?.jsonPrimitive?.content ?: "rear"
            val quality = params["jpeg_quality"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 100) ?: 90
            captureToFile(outputFile, lens, quality)
            Log.i(TAG, "[Camera] Capture complete for session=$sessionId, starting upload")
            ConnectionStatusHolder.setStatus("Uploading photo for session $sessionId")
            val uploadResult = transferClient.upload(
                sessionId = sessionId,
                sessionType = "camera_snapshot",
                file = outputFile,
            )
            if (uploadResult.isSuccess) {
                Log.i(TAG, "[Camera] Upload succeeded for session=$sessionId, deleting temp file")
                ConnectionStatusHolder.setStatus("Upload complete for session $sessionId")
                outputFile.delete()
                CapabilityResult.Success(buildJsonObject { put("uploaded", true) })
            } else {
                Log.e(TAG, "[Camera] Upload failed for session=$sessionId", uploadResult.exceptionOrNull())
                ConnectionStatusHolder.setError("Upload failed for session $sessionId")
                CapabilityResult.Failure(
                    errorCode = ErrorCode.TRANSFER_FAILED,
                    message = uploadResult.exceptionOrNull()?.message ?: "upload failed",
                    recommendedAction = "Retry once network connectivity to the hub is restored.",
                )
            }
        } catch (e: ImageCaptureException) {
            Log.e(TAG, "capture failed", e)
            CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Camera capture failed: ${e.message}")
        }
    }

    private suspend fun waitForForeground(sessionId: String, timeoutMs: Long = 15_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (foregroundGate.isAppForegrounded()) {
                Log.i(TAG, "[Camera] App reached foreground for session=$sessionId")
                ConnectionStatusHolder.setStatus("Phone awakened for session $sessionId")
                return true
            }
            delay(250)
        }
        Log.w(TAG, "[Camera] Timed out waiting for foreground for session=$sessionId")
        ConnectionStatusHolder.setError("Timed out waiting for phone to wake for session $sessionId")
        return false
    }

    private suspend fun captureToFile(outputFile: File, lens: String, jpegQuality: Int) = suspendCancellableCoroutine<Unit> { cont ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(jpegQuality)
                .build()

            try {
                provider.unbindAll()
                val selector = if (lens == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                foregroundGate.bindCameraUseCase(provider, imageCapture, selector)

                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d(TAG, "[Camera] Image saved to ${outputFile.absolutePath}")
                            provider.unbindAll()
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "[Camera] Image capture error", exc)
                            provider.unbindAll()
                            if (cont.isActive) cont.cancel(exc)
                        }
                    },
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.cancel(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

interface ForegroundGate {
    fun isAppForegrounded(): Boolean
    fun requestForeground()
    fun bindCameraUseCase(
        provider: ProcessCameraProvider,
        imageCapture: ImageCapture,
        selector: CameraSelector,
    )
}

class DefaultForegroundGate(private val context: Context) : ForegroundGate {
    @Volatile private var activityRef: CompanionActivity? = null

    companion object {
        private const val WAKE_NOTIFICATION_ID = 1003
    }

    fun attach(activity: CompanionActivity) {
        activityRef = activity
    }

    fun detach(activity: CompanionActivity) {
        if (activityRef === activity) activityRef = null
    }

    override fun isAppForegrounded(): Boolean {
        val activity = activityRef
        return activity != null && activity.isResumedState()
    }

    override fun requestForeground() {
        val fullScreenIntent = Intent(context, CompanionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(CompanionActivity.EXTRA_WAKE_REASON, "camera_capture")
        }
        
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, EcosystemAgentService.URGENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Ecosystem Hub Request")
            .setContentText("Camera access requested by hub.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(WAKE_NOTIFICATION_ID, notificationBuilder.build())
    }

    override fun bindCameraUseCase(
        provider: ProcessCameraProvider,
        imageCapture: ImageCapture,
        selector: CameraSelector,
    ) {
        val activity = activityRef ?: throw IllegalStateException("No foregrounded CompanionActivity to bind camera lifecycle to")
        provider.bindToLifecycle(activity, selector, imageCapture)
    }
}
