package com.ecosystem.agent.ui

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ecosystem.agent.AgentDeviceAdminReceiver
import com.ecosystem.agent.config.AgentPreferences
import com.ecosystem.agent.service.EcosystemAgentService
import com.ecosystem.agent.state.ConnectionStatusHolder
import com.ecosystem.agent.transfer.FileTransferClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class CompanionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_WAKE_REASON = "wake_reason"
    }

    private var resumed = false
    private var adminActive = mutableStateOf(false)
    private var pendingAgentStart = false
    private val transferStatus = mutableStateOf<String?>(null)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) uploadSelectedFiles(uris)
    }

    fun isResumedState(): Boolean = resumed

    /**
     * Checks if the device keyguard is currently locked.
     * Public so it can be called from StatusScreen UI components.
     */
    fun checkDeviceLocked(): Boolean {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardLocked
    }

    private val adminLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleAdminResult(result.resultCode)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!requiredPermissionsGranted()) {
            ConnectionStatusHolder.setError("Permissions required for camera/mic access.")
            return@registerForActivityResult
        }
        ensureAgentServiceRunning()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure the activity can show over the lock screen for wake-and-capture.
        // programmatic API is more reliable on modern Android versions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val prefs = AgentPreferences(this)
        adminActive.value = isDeviceAdminActive()
        
        if (intent.getStringExtra(EXTRA_WAKE_REASON) != null) {
            setContent { WakeIndicatorScreen() }
        } else {
            setContent {
                AppRoot(
                    prefs = prefs,
                    adminEnabled = adminActive.value,
                    onEnableAdmin = { requestAdminActivation() },
                    onTriggerPermissions = { requestRuntimePermissions() },
                    onChooseFiles = { filePicker.launch(arrayOf("*/*")) },
                    onSendClipboard = { text -> sendTextToHub(text) },
                    transferStatus = transferStatus.value,
                    onReconnect = { ensureAgentServiceRunning() },
                )
            }
        }
        if (prefs.isConfigured()) {
            if (requiredPermissionsGranted()) ensureAgentServiceRunning() else requestRuntimePermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        EcosystemAgentService.foregroundGate?.attach(this)

        if (intent.getStringExtra(EXTRA_WAKE_REASON) != null) {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            // Requesting keyguard dismissal makes the activity interactive immediately
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }

    override fun onPause() {
        resumed = false
        EcosystemAgentService.foregroundGate?.detach(this)
        super.onPause()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requiredPermissionsGranted(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return camera == PackageManager.PERMISSION_GRANTED &&
            mic == PackageManager.PERMISSION_GRANTED && location == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureAgentServiceRunning(): Boolean {
        val intent = Intent(this, EcosystemAgentService::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(this, AgentDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(component)
    }

    private fun requestAdminActivation() {
        val component = ComponentName(this, AgentDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable admin mode so the agent can wake the phone and attempt camera capture while locked during testing.",
            )
        }
        pendingAgentStart = true
        adminLauncher.launch(intent)
    }

    private fun handleAdminResult(resultCode: Int) {
        adminActive.value = isDeviceAdminActive()
        if (adminActive.value) {
            ConnectionStatusHolder.setStatus("Admin mode enabled")
            if (pendingAgentStart) {
                pendingAgentStart = false
                requestRuntimePermissions()
            }
        } else if (pendingAgentStart) {
            pendingAgentStart = false
            ConnectionStatusHolder.setError("Admin mode was not enabled")
            Toast.makeText(this, "Admin mode not enabled.", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendTextToHub(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Enter or paste text first.", Toast.LENGTH_LONG).show()
            return
        }
        val sent = EcosystemAgentService.sendClipboardToHub(text)
        Toast.makeText(this, if (sent) "Clipboard sent to Arch." else "Hub is not connected.", Toast.LENGTH_LONG).show()
    }

    private fun uploadSelectedFiles(uris: List<Uri>) {
        transferStatus.value = "Sending ${uris.size} file(s)..."
        lifecycleScope.launch {
            val prefs = AgentPreferences(this@CompanionActivity)
            val client = FileTransferClient(prefs.transferBaseUrl(), prefs.deviceId())
            var succeeded = 0
            uris.forEach { uri ->
                val name = displayName(uri)
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "shared_file" }
                val temp = File(cacheDir, "send_${UUID.randomUUID()}_$safeName")
                val copied = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot open selected file")
                }.isSuccess
                if (copied && client.upload(UUID.randomUUID().toString(), "manual_file", temp).isSuccess) succeeded++
                temp.delete()
            }
            transferStatus.value = "Sent $succeeded of ${uris.size} file(s) to Arch"
            Toast.makeText(this@CompanionActivity, transferStatus.value, Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0) ?: "shared_file" else "shared_file"
        } finally {
            cursor?.close()
        }
    }
}

@Composable
fun AppRoot(
    prefs: AgentPreferences,
    adminEnabled: Boolean,
    onEnableAdmin: () -> Unit,
    onTriggerPermissions: () -> Unit,
    onChooseFiles: () -> Unit,
    onSendClipboard: (String) -> Unit,
    transferStatus: String?,
    onReconnect: () -> Unit,
) {
    var showSetup by remember { mutableStateOf(!prefs.isConfigured()) }

    if (showSetup) {
        SetupScreen(
            prefs,
            adminEnabled = adminEnabled,
            onEnableAdmin = onEnableAdmin,
            onSaved = {
            showSetup = false
            if (adminEnabled) {
                onTriggerPermissions()
            } else {
                onEnableAdmin()
            }
        })
    } else {
        StatusScreen(prefs, adminEnabled = adminEnabled, onEdit = { showSetup = true }, onEnableAdmin = onEnableAdmin, onChooseFiles = onChooseFiles, onSendClipboard = onSendClipboard, transferStatus = transferStatus, onReconnect = onReconnect, onGrantPermissions = onTriggerPermissions)
    }
}

@Composable
fun SetupScreen(
    prefs: AgentPreferences,
    adminEnabled: Boolean,
    onEnableAdmin: () -> Unit,
    onSaved: () -> Unit,
) {
    var wsUrl by remember { mutableStateOf(prefs.wsUrl()) }
    var transferUrl by remember { mutableStateOf(prefs.transferBaseUrl()) }
    var displayName by remember { mutableStateOf(prefs.displayName()) }

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Ecosystem Agent Setup", style = MaterialTheme.typography.headlineSmall)
        Text("Configure connection to your Linux hub", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        StatusBanner(
            adminEnabled = adminEnabled,
            locked = false,
            readyText = if (adminEnabled) "Ready for locked capture testing" else "Enable admin mode to test locked capture",
        )
        Spacer(Modifier.height(16.dp))
        
        OutlinedTextField(value = wsUrl, onValueChange = { wsUrl = it }, label = { Text("Hub WebSocket URL (ws://IP:8765)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        
        OutlinedTextField(value = transferUrl, onValueChange = { transferUrl = it }, label = { Text("Hub Transfer URL (http://IP:8766)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        
        OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Display Name") }, modifier = Modifier.fillMaxWidth())
        
        Spacer(Modifier.height(24.dp))
        
        Button(onClick = {
            prefs.saveSetup(wsUrl, transferUrl, displayName)
            onSaved()
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Save & Start Agent")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onEnableAdmin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !adminEnabled,
        ) {
            Text(if (adminEnabled) "Admin Mode Enabled" else "Enable Admin Mode for Locked Capture")
        }

        Spacer(Modifier.height(16.dp))
        
        TextButton(onClick = { 
            prefs.clearConfig()
            wsUrl = ""
            transferUrl = ""
        }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Default.DeleteForever, null)
            Spacer(Modifier.width(8.dp))
            Text("Clear All Settings")
        }
    }
}

@Composable
fun StatusScreen(prefs: AgentPreferences, adminEnabled: Boolean, onEdit: () -> Unit, onEnableAdmin: () -> Unit, onChooseFiles: () -> Unit, onSendClipboard: (String) -> Unit, transferStatus: String?, onReconnect: () -> Unit, onGrantPermissions: () -> Unit) {
    val status by ConnectionStatusHolder.status.collectAsState()
    val connected by ConnectionStatusHolder.connected.collectAsState()
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    fun clipboardText() = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    var outgoingText by remember { mutableStateOf("") }
    var receivedText by remember { mutableStateOf(clipboardText()) }
    DisposableEffect(clipboard) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { receivedText = clipboardText() }
        clipboard.addPrimaryClipChangedListener(listener)
        onDispose { clipboard.removePrimaryClipChangedListener(listener) }
    }
    val locked by produceState(initialValue = false) {
        val activity = context as? CompanionActivity
        while (true) {
            value = activity?.checkDeviceLocked() ?: false
            delay(750)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Ecosystem", style = MaterialTheme.typography.headlineSmall)
                Text("Android companion", style = MaterialTheme.typography.bodySmall)
            }
            if (connected) {
                Icon(Icons.Filled.CheckCircle, "Connected", tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Filled.Warning, "Disconnected", tint = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(16.dp))

        StatusBanner(
            adminEnabled = adminEnabled,
            locked = locked,
            readyText = if (locked) "Locked screen testing active" else "Phone is unlocked and ready",
        )
        Spacer(Modifier.height(8.dp))
        
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
        )) {
            Text(status, Modifier.padding(16.dp))
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
            Text("Reconnect now")
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Settings, null)
            Spacer(Modifier.width(8.dp))
            Text("Edit Configuration / Change IP")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onEnableAdmin, modifier = Modifier.fillMaxWidth(), enabled = !adminEnabled) {
            Text(if (adminEnabled) "Admin Mode Enabled" else "Enable Admin Mode for Locked Capture")
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Hub Connection", style = MaterialTheme.typography.titleMedium)
                Text(prefs.wsUrl(), style = MaterialTheme.typography.bodySmall)
                Text(prefs.transferBaseUrl(), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Clipboard workspace", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = outgoingText,
                    onValueChange = { outgoingText = it.take(65536) },
                    label = { Text("Type or paste text for Arch") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSendClipboard(outgoingText) }, enabled = connected, modifier = Modifier.weight(1f)) { Text("Send to Arch") }
                    OutlinedButton(onClick = { outgoingText = clipboardText() }, modifier = Modifier.weight(1f)) { Text("Paste phone") }
                }
                Spacer(Modifier.height(8.dp))
                Text("Received from Arch", style = MaterialTheme.typography.labelLarge)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(receivedText.ifBlank { "Nothing received yet" }, Modifier.padding(12.dp))
                }
                OutlinedButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Ecosystem", receivedText)) }, enabled = receivedText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Copy received text") }
                Spacer(Modifier.height(10.dp))
                Text("Files", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChooseFiles, enabled = connected, modifier = Modifier.weight(1f)) { Text("Choose files") }
                }
                transferStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Permissions & capabilities", style = MaterialTheme.typography.titleMedium)
                Text("Camera / Microphone / Location / Notifications", style = MaterialTheme.typography.bodyMedium)
                Text("Required for snapshots, live camera, audio, and location.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGrantPermissions, modifier = Modifier.fillMaxWidth()) { Text("Review permissions") }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Available from Arch", style = MaterialTheme.typography.titleMedium)
                Text("Camera · Audio · Physical sensors · Location", style = MaterialTheme.typography.bodyMedium)
                Text("Screen recording uses an Android consent dialog for every recording.", style = MaterialTheme.typography.bodySmall)
                Text("Clipboard send works remotely; reading may require this screen to be visible.", style = MaterialTheme.typography.bodySmall)
                Text("Transferred files and media are browsed in the Arch Files and Camera pages.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Connection diagnostics remain available through Arch journalctl and Android logcat.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusBanner(
    adminEnabled: Boolean,
    locked: Boolean,
    readyText: String,
) {
    val background = when {
        !adminEnabled -> MaterialTheme.colorScheme.errorContainer
        locked -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val label = when {
        !adminEnabled -> "Admin mode is OFF"
        locked -> "Phone is locked"
        else -> "Phone is unlocked"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(readyText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun WakeIndicatorScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
