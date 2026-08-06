package com.ecosystem.agent.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ecosystem.agent.net.CommandName
import com.ecosystem.agent.net.ErrorCode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

class LocationController(private val context: Context, private val emit: (JsonObject) -> Unit) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null
    fun hasFine() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasCoarse() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun enabledProviders() = manager.getProviders(true)
    fun permissionState() = when { hasFine() -> "granted"; hasCoarse() -> "foreground_only"; else -> "not_requested" }
    fun emitLocation(payload: JsonObject) = emit(payload)

    @Suppress("MissingPermission")
    suspend fun current(): Result<Location> {
        if (!hasCoarse() && !hasFine()) return Result.failure(SecurityException("Location permission is not granted"))
        val providers = enabledProviders().filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        if (providers.isEmpty()) return Result.failure(IllegalStateException("LOCATION_DISABLED"))
        val cached = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (cached != null && System.currentTimeMillis() - cached.time <= 30_000L) return Result.success(cached)
        val fresh = withTimeoutOrNull(25_000) { suspendCancellableCoroutine<Location> { continuation ->
            val oneShot = object : LocationListener {
                override fun onLocationChanged(location: Location) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(location) }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, oneShot, Looper.getMainLooper()) }
            continuation.invokeOnCancellation { manager.removeUpdates(oneShot) }
        } }
        return (fresh ?: cached)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("NO_LOCATION_FIX"))
    }

    @Suppress("MissingPermission")
    fun start(): Result<Unit> = runCatching {
        if (!hasCoarse() && !hasFine()) throw SecurityException("Location permission is not granted")
        stop()
        val providers = enabledProviders().filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        if (providers.isEmpty()) throw IllegalStateException("LOCATION_DISABLED")
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = emit(locationPayload("location.sample", location))
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { emit(buildJsonObject { put("event", "location.error"); put("error", "PROVIDER_DISABLED"); put("provider", provider) }) }
            @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }.also { target -> providers.forEach { manager.requestLocationUpdates(it, 1_000L, 0f, target, Looper.getMainLooper()) } }
    }
    fun stop() { listener?.let { manager.removeUpdates(it) }; listener = null }
}

object LocationFreshness { const val MAX_FRESH_AGE_MS = 30_000L; fun age(nowMs: Long, fixMs: Long) = (nowMs-fixMs).coerceAtLeast(0); fun isStale(nowMs: Long, fixMs: Long) = age(nowMs,fixMs)>MAX_FRESH_AGE_MS }
private fun locationPayload(event: String, location: Location): JsonObject = buildJsonObject {
    put("event", event); put("latitude", location.latitude); put("longitude", location.longitude)
    put("accuracy", location.accuracy); put("provider", location.provider ?: "unknown"); put("fix_timestamp_ms", location.time)
    val now=System.currentTimeMillis();put("fix_age_ms", LocationFreshness.age(now,location.time));put("stale",LocationFreshness.isStale(now,location.time)); put("precise", location.accuracy <= 100f)
    if (location.hasAltitude()) put("altitude", location.altitude); if (location.hasSpeed()) put("speed", location.speed)
    if (location.hasBearing()) put("bearing", location.bearing)
}

class LocationCapability(private val id: String, private val controller: LocationController) : Capability {
    override val name = id
    override val handledCommands = setOf(CommandName.LOCATION_CURRENT, CommandName.LOCATION_STREAM_START, CommandName.LOCATION_STREAM_STOP)
    override fun isPermissionGranted() = controller.hasCoarse() || controller.hasFine()
    override fun permissionState() = controller.permissionState()
    override fun provider() = "android.location_manager"
    override fun restrictionReason(): String? = when { !isPermissionGranted() -> "Location permission must be granted on the phone."; controller.enabledProviders().isEmpty() -> "Location is disabled in Android settings."; else -> null }
    override fun metadata() = buildJsonObject { put("providers", controller.enabledProviders().joinToString(",")); put("fine_location", controller.hasFine()); put("coarse_location", controller.hasCoarse()) }
    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult = when (command) {
        CommandName.LOCATION_CURRENT -> controller.current().fold({ location ->
            val payload = locationPayload("location.sample", location); controller.emitLocation(payload); CapabilityResult.Success(payload)
        }, ::failure)
        CommandName.LOCATION_STREAM_START -> controller.start().fold({ CapabilityResult.Success() }, ::failure)
        CommandName.LOCATION_STREAM_STOP -> { controller.stop(); CapabilityResult.Success() }
        else -> CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Unsupported location operation")
    }
    private fun failure(error: Throwable) = when (error) {
        is SecurityException -> CapabilityResult.Failure(ErrorCode.PERMISSION_DENIED, error.message ?: "Location permission missing", "Grant location permission on the phone.", true, Manifest.permission.ACCESS_COARSE_LOCATION)
        else -> CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, error.message ?: "Location request failed", "Enable Location and try outdoors for a GPS fix.")
    }
}
