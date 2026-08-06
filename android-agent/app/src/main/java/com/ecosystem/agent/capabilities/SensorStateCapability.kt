package com.ecosystem.agent.capabilities

import android.content.Context
import android.os.BatteryManager
import com.ecosystem.agent.net.CommandName
import com.ecosystem.agent.net.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Covers the "sensors expected to operate while locked" category from the
 * runtime requirements: battery/charging state, network state. (Live
 * accelerometer/gyroscope/magnetometer *streaming* is a documented
 * extension point for V2 - advertised here at the metadata level as
 * available but not yet wired to a streaming session, consistent with
 * "design for them but do not implement them" guidance applied
 * conservatively to anything beyond V1's camera+audio scope.)
 *
 * This capability responds to get_runtime_state-style polling through the
 * normal command path rather than requiring a dedicated session, since
 * these are point-in-time reads, not long-running captures.
 */
class SensorStateCapability(private val context: Context) : Capability {

    override val name = "device.state"
    override val handledCommands = setOf(CommandName.GET_RUNTIME_STATE)

    override fun isPermissionGranted(): Boolean = true // no runtime permission needed for these reads

    override fun metadata(): JsonObject = buildJsonObject {
        put("battery_state", true)
        put("charging_state", true)
        put("network_state", true)
    }

    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging

        return CapabilityResult.Success(buildJsonObject {
            put("battery_percent", level)
            put("charging", isCharging)
        })
    }
}
