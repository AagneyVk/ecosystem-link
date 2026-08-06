package com.ecosystem.agent.state

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ecosystem.agent.capabilities.CapabilityRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Assembles the runtime-state snapshot the hub needs to always know:
 * VPN connectivity, per-capability permission grants, active foreground
 * services, and currently-active Android restrictions. Sent at handshake
 * and pushed as `state_update` messages whenever something changes (e.g.
 * a permission is revoked while the agent is running, or a foreground
 * service starts/stops).
 */
class RuntimeStateReporter(
    private val context: Context,
    private val capabilityRegistry: CapabilityRegistry,
    private val vpnInterfaceNameHint: String = "tun",
) {
    @Volatile private var activeServices: MutableMap<String, String> = mutableMapOf()
    @Volatile private var restrictions: MutableList<String> = mutableListOf()

    fun reportServiceState(serviceName: String, state: String) {
        activeServices[serviceName] = state
    }

    fun reportRestriction(restriction: String, active: Boolean) {
        if (active) {
            if (restriction !in restrictions) restrictions.add(restriction)
        } else {
            restrictions.remove(restriction)
        }
    }

    fun isVpnConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun currentStateAsJson(): JsonObject = buildJsonObject {
        put("vpn_connected", isVpnConnected())
        put("permissions", buildJsonObject {
            capabilityRegistry.all().forEach { cap ->
                put(cap.name, cap.isPermissionGranted())
            }
        })
        put("active_services", buildJsonObject {
            activeServices.forEach { (name, state) -> put(name, state) }
        })
        put("restrictions", kotlinx.serialization.json.JsonArray(
            restrictions.map { kotlinx.serialization.json.JsonPrimitive(it) }
        ))
    }
}
