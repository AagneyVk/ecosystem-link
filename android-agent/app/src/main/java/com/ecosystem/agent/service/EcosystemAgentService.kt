package com.ecosystem.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ecosystem.agent.capabilities.CapabilityRegistry
import com.ecosystem.agent.capabilities.CameraCapability
import com.ecosystem.agent.capabilities.CameraStreamCapability
import com.ecosystem.agent.capabilities.DefaultForegroundGate
import com.ecosystem.agent.capabilities.MicrophoneCapability
import com.ecosystem.agent.capabilities.SensorStateCapability
import com.ecosystem.agent.capabilities.PhysicalSensorCapability
import com.ecosystem.agent.capabilities.SensorStreamController
import com.ecosystem.agent.capabilities.LocationCapability
import com.ecosystem.agent.capabilities.LocationController
import com.ecosystem.agent.capabilities.ScreenRecordingCapability
import com.ecosystem.agent.capabilities.ClipboardCapability
import com.ecosystem.agent.capabilities.FileReceiveCapability
import com.ecosystem.agent.config.AgentPreferences
import com.ecosystem.agent.net.EcosystemWebSocketClient
import com.ecosystem.agent.session.SessionManager
import com.ecosystem.agent.state.ConnectionStatusHolder
import com.ecosystem.agent.state.RuntimeStateReporter
import com.ecosystem.agent.transfer.FileTransferClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "EcosystemService"

class EcosystemAgentService : Service(), LifecycleOwner {

    companion object {
        const val CHANNEL_ID = "ecosystem_agent"
        const val URGENT_CHANNEL_ID = "ecosystem_urgent"
        const val NOTIFICATION_ID = 1001

        @Volatile var foregroundGate: DefaultForegroundGate? = null
            private set
        @Volatile private var activeInstance: EcosystemAgentService? = null

        fun sendClipboardToHub(text: String): Boolean {
            val service = activeInstance ?: return false
            val client = service.wsClient ?: return false
            if (!client.isConnected()) return false
            client.sendEvent(buildJsonObject {
                put("event", "clipboard.updated")
                put("text", text.take(65536))
            })
            return true
        }

        fun reportSessionState(sessionId: String, state: String, error: String? = null) {
            activeInstance?.wsClient?.sendSessionEvent(sessionId, state, error)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var wsClient: EcosystemWebSocketClient? = null
    private lateinit var runtimeStateReporter: RuntimeStateReporter
    private lateinit var capabilityRegistry: CapabilityRegistry
    private lateinit var sessionManager: SessionManager
    private lateinit var sensorController: SensorStreamController
    private lateinit var locationController: LocationController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        activeInstance = this
        createChannelIfNeeded()

        capabilityRegistry = CapabilityRegistry()
        sessionManager = SessionManager()
        runtimeStateReporter = RuntimeStateReporter(this, capabilityRegistry)

        val gate = DefaultForegroundGate(this)
        foregroundGate = gate

        val prefs = AgentPreferences(this)
        val transferClient = FileTransferClient(prefs.transferBaseUrl(), prefs.deviceId())
        val foregroundServiceController = DefaultForegroundServiceController(this)

        capabilityRegistry.register(SensorStateCapability(this))
        sensorController = SensorStreamController(this) { payload -> wsClient?.sendEvent(payload) }
        sensorController.sensors().forEach { capabilityRegistry.register(PhysicalSensorCapability(it, sensorController)) }
        locationController = LocationController(this) { payload -> wsClient?.sendEvent(payload) }
        capabilityRegistry.register(LocationCapability("location.current", locationController))
        capabilityRegistry.register(LocationCapability("location.stream", locationController))
        capabilityRegistry.register(ScreenRecordingCapability(this))
        capabilityRegistry.register(ClipboardCapability(this))
        capabilityRegistry.register(FileReceiveCapability(this))
        capabilityRegistry.register(CameraCapability(this, sessionManager, transferClient, gate))

        // Register streaming capabilities with their session-type mapping so that
        // handleSessionControl() in EcosystemWebSocketClient can resolve and call them.
        val streamingBaseUrl = prefs.transferBaseUrl()
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .plus("/stream")
        capabilityRegistry.registerStreamingCapability(
            "microphone_stream",
            MicrophoneCapability(
                this, sessionManager, transferClient, foregroundServiceController,
                streamingBaseUrl, prefs.deviceId(),
            ),
        )
        capabilityRegistry.registerStreamingCapability(
            "camera_stream",
            CameraStreamCapability(
                context = this,
                foregroundServiceController = foregroundServiceController,
                streamingBaseUrl = streamingBaseUrl,
                deviceId = prefs.deviceId(),
                foregroundGate = gate,
            )
        )

        sessionManager.onSessionChanged { session ->
            wsClient?.sendSessionEvent(session.sessionId, session.state, session.lastError)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = AgentPreferences(this)
        
        if (!prefs.isConfigured()) {
            Log.i(TAG, "Service started but not configured. Waiting...")
            showForegroundNotification("Waiting for configuration")
            return START_STICKY
        }

        showForegroundNotification("Connecting to Linux hub...")
        
        wsClient?.stop()
        wsClient = EcosystemWebSocketClient(
            hubUrl = prefs.wsUrl(),
            deviceId = prefs.deviceId(),
            displayName = prefs.displayName(),
            capabilityRegistry = capabilityRegistry,
            sessionManager = sessionManager,
            runtimeStateReporter = runtimeStateReporter,
            scope = serviceScope,
        ).apply {
            onConnectionStateChanged = { connected ->
                ConnectionStatusHolder.setConnected(connected)
            }
            start()
        }

        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                wsClient?.sendStateUpdate(runtimeStateReporter.currentStateAsJson())
            }
        }

        return START_STICKY
    }

    private fun showForegroundNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ecosystem Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        sensorController.stopAll()
        locationController.stop()
        wsClient?.stop()
        heartbeatJob?.cancel()
        serviceScope.cancel()
        foregroundGate = null
        activeInstance = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ecosystem Agent", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (manager.getNotificationChannel(URGENT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(URGENT_CHANNEL_ID, "Ecosystem Urgent", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Used for waking the app for urgent hub requests"
                }
            )
        }
    }
}
