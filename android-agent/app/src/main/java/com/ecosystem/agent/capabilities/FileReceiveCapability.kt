package com.ecosystem.agent.capabilities

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ecosystem.agent.net.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class FileReceiveCapability(private val context: Context) : Capability {
    override val name = "files.receive"
    override val handledCommands = setOf("file_receive")
    override fun isPermissionGranted() = true
    override fun provider() = "android.downloads"
    override fun metadata() = buildJsonObject { put("destination", "Downloads/Ecosystem"); put("maximum_bytes", 100 * 1024 * 1024) }

    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult = withContext(Dispatchers.IO) {
        val url = params["url"]?.jsonPrimitive?.content
            ?: return@withContext CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Missing download URL")
        val filename = safeName(params["filename"]?.jsonPrimitive?.content ?: "ecosystem_file")
        val expected = params["sha256"]?.jsonPrimitive?.content.orEmpty()
        try {
            val bytes = OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("Download returned HTTP ${response.code}")
                val body = response.body?.bytes() ?: error("Empty download body")
                if (body.size > 100 * 1024 * 1024) error("File exceeds 100 MiB")
                body
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (expected.isNotBlank() && !actual.equals(expected, true)) error("Checksum mismatch")
            val destination = save(filename, bytes)
            CapabilityResult.Success(buildJsonObject { put("filename", filename); put("destination", destination); put("size_bytes", bytes.size); put("sha256", actual) })
        } catch (e: Exception) {
            CapabilityResult.Failure(ErrorCode.TRANSFER_FAILED, e.message ?: "File download failed", "Confirm the transfer URL is reachable over the VPN.")
        }
    }

    private fun save(filename: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Ecosystem")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android Downloads provider rejected the file")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write download")
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return "Downloads/Ecosystem/$filename"
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        }
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(directory, filename); file.parentFile?.mkdirs(); file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun safeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(160).ifBlank { "ecosystem_file" }
}
