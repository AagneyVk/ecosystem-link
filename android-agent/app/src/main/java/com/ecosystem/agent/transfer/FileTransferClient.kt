package com.ecosystem.agent.transfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "FileTransferClient"

/**
 * Uploads captured artifacts to the hub's dedicated file-transfer HTTP
 * endpoint (never through the WebSocket control channel - see the
 * file-transfer requirements). Computes SHA-256 locally, sends it as a
 * header, and treats anything other than HTTP 200 as a failed transfer
 * that must NOT result in local file deletion.
 */
class FileTransferClient(
    private val transferBaseUrl: String, // e.g. http://hub.internal:8766
    private val deviceId: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun upload(sessionId: String, sessionType: String, file: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val checksum = sha256(file)
            val url = "$transferBaseUrl/upload/$deviceId/$sessionId/${file.name}"
            Log.i(TAG, "[Upload] Starting upload session=$sessionId type=$sessionType url=$url")
            Log.d(TAG, "[Upload] Local file=${file.absolutePath} size=${file.length()} checksum=$checksum")

            val mediaType = mediaTypeFor(file.name)
            val body = file.asRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .put(body)
                .addHeader("X-Checksum-SHA256", checksum)
                .addHeader("X-Session-Type", sessionType)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "upload succeeded for session $sessionId (${file.length()} bytes)")
                    Result.success(Unit)
                } else {
                    val body = response.body?.string() ?: ""
                    Log.w(TAG, "upload failed: ${response.code} $body")
                    Result.failure(TransferException("HTTP ${response.code}: $body"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload exception for session $sessionId", e)
            Result.failure(e)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mediaTypeFor(filename: String) = when {
        filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg".toMediaType()
        filename.endsWith(".m4a") -> "audio/mp4".toMediaType()
        else -> "application/octet-stream".toMediaType()
    }
}

class TransferException(message: String) : Exception(message)
