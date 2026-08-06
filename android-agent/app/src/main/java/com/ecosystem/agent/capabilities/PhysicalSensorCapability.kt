package com.ecosystem.agent.capabilities

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.ecosystem.agent.net.CommandName
import com.ecosystem.agent.net.ErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

data class SensorDescriptor(val capabilityId: String, val displayName: String, val unit: String)

object SensorMapping {
    private val common = mapOf(
        Sensor.TYPE_ACCELEROMETER to SensorDescriptor("sensor.accelerometer", "Accelerometer", "m/s²"),
        Sensor.TYPE_GYROSCOPE to SensorDescriptor("sensor.gyroscope", "Gyroscope", "rad/s"),
        Sensor.TYPE_MAGNETIC_FIELD to SensorDescriptor("sensor.magnetic_field", "Magnetic field", "µT"),
        Sensor.TYPE_GRAVITY to SensorDescriptor("sensor.gravity", "Gravity", "m/s²"),
        Sensor.TYPE_LINEAR_ACCELERATION to SensorDescriptor("sensor.linear_acceleration", "Linear acceleration", "m/s²"),
        Sensor.TYPE_ROTATION_VECTOR to SensorDescriptor("sensor.rotation_vector", "Rotation vector", "unitless"),
        Sensor.TYPE_GAME_ROTATION_VECTOR to SensorDescriptor("sensor.game_rotation_vector", "Game rotation vector", "unitless"),
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR to SensorDescriptor("sensor.geomagnetic_rotation_vector", "Geomagnetic rotation", "unitless"),
        Sensor.TYPE_LIGHT to SensorDescriptor("sensor.light", "Ambient light", "lx"),
        Sensor.TYPE_PROXIMITY to SensorDescriptor("sensor.proximity", "Proximity", "cm"),
        Sensor.TYPE_PRESSURE to SensorDescriptor("sensor.pressure", "Pressure", "hPa"),
        Sensor.TYPE_STEP_DETECTOR to SensorDescriptor("sensor.step_detector", "Step detector", "step"),
        Sensor.TYPE_STEP_COUNTER to SensorDescriptor("sensor.step_counter", "Step counter", "steps"),
        Sensor.TYPE_RELATIVE_HUMIDITY to SensorDescriptor("sensor.relative_humidity", "Relative humidity", "%"),
        Sensor.TYPE_AMBIENT_TEMPERATURE to SensorDescriptor("sensor.ambient_temperature", "Ambient temperature", "°C"),
    )
    fun descriptor(sensor: Sensor): SensorDescriptor = common[sensor.type]
        ?: SensorDescriptor("sensor.android_${sensor.type}", sensor.name, "device-specific")
}

class SensorStreamController(context: Context, private val emit: (JsonObject) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val active = ConcurrentHashMap<Int, Sensor>()
    private val lastSent = ConcurrentHashMap<Int, Long>()
    @Volatile private var minimumIntervalNs = 50_000_000L

    fun sensors(): List<Sensor> = manager.getSensorList(Sensor.TYPE_ALL)
    fun sensorForCapability(id: String): Sensor? = sensors().firstOrNull { SensorMapping.descriptor(it).capabilityId == id }
    fun start(sensor: Sensor, preset: String): Boolean {
        if (active.putIfAbsent(sensor.type, sensor) != null) return true
        val (androidDelay, outboundNs) = when (preset) {
            "low" -> SensorManager.SENSOR_DELAY_NORMAL to 250_000_000L
            "high" -> SensorManager.SENSOR_DELAY_GAME to 25_000_000L
            "fastest-safe" -> SensorManager.SENSOR_DELAY_FASTEST to 20_000_000L
            else -> SensorManager.SENSOR_DELAY_UI to 50_000_000L
        }
        minimumIntervalNs = outboundNs
        if (!manager.registerListener(this, sensor, androidDelay, 250_000)) {
            active.remove(sensor.type); return false
        }
        return true
    }
    fun stop(sensorType: Int) {
        val sensor = active.remove(sensorType) ?: return
        manager.unregisterListener(this, sensor)
        lastSent.remove(sensorType)
        emit(buildJsonObject {
            put("event", "sensor.stream_state")
            put("capability", SensorMapping.descriptor(sensor).capabilityId)
            put("running", false)
        })
    }
    fun stopAll() { active.clear(); manager.unregisterListener(this) }
    override fun onSensorChanged(event: SensorEvent) {
        if (!active.containsKey(event.sensor.type)) return
        val previous = lastSent[event.sensor.type] ?: 0L
        if (event.timestamp - previous < minimumIntervalNs) return
        lastSent[event.sensor.type] = event.timestamp
        val descriptor = SensorMapping.descriptor(event.sensor)
        emit(buildJsonObject { put("event", "sensor.sample"); put("capability", descriptor.capabilityId)
            put("sensor_timestamp_ns", event.timestamp); put("accuracy", event.accuracy)
            put("values", JsonArray(event.values.map(::JsonPrimitive))) })
    }
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        emit(buildJsonObject { put("event", "sensor.accuracy"); put("capability", SensorMapping.descriptor(sensor).capabilityId); put("accuracy", accuracy) })
    }
}

class PhysicalSensorCapability(private val sensor: Sensor, private val controller: SensorStreamController) : Capability {
    private val descriptor = SensorMapping.descriptor(sensor)
    override val name = descriptor.capabilityId
    override val handledCommands = setOf(CommandName.SENSOR_STREAM_START, CommandName.SENSOR_STREAM_STOP)
    override fun isPermissionGranted() = true
    override fun provider() = "android.sensor_manager"
    override fun metadata(): JsonObject = buildJsonObject {
        put("android_sensor_type", sensor.type); put("display_name", descriptor.displayName); put("unit", descriptor.unit)
        put("vendor", sensor.vendor); put("version", sensor.version); put("maximum_range", sensor.maximumRange)
        put("resolution", sensor.resolution); put("power_ma", sensor.power); put("minimum_delay_us", sensor.minDelay)
        if (Build.VERSION.SDK_INT >= 21) { put("maximum_delay_us", sensor.maxDelay); put("reporting_mode", sensor.reportingMode); put("wake_up", sensor.isWakeUpSensor) }
        put("fifo_max_event_count", sensor.fifoMaxEventCount); put("fifo_reserved_event_count", sensor.fifoReservedEventCount)
    }
    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult {
        val requested = params["sensor"]?.jsonPrimitive?.content ?: name
        val target = controller.sensorForCapability(requested)
            ?: return CapabilityResult.Failure(ErrorCode.CAPABILITY_NOT_FOUND, "Sensor $requested is not present")
        return when (command) {
            CommandName.SENSOR_STREAM_START -> if (controller.start(target, params["preset"]?.jsonPrimitive?.content ?: "normal")) CapabilityResult.Success(buildJsonObject { put("running", true) }) else CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Android rejected sensor listener registration")
            CommandName.SENSOR_STREAM_STOP -> { controller.stop(target.type); CapabilityResult.Success() }
            else -> CapabilityResult.Failure(ErrorCode.INTERNAL_ERROR, "Unsupported sensor operation")
        }
    }
}
