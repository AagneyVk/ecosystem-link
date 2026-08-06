# Sensors

Android enumerates `SensorManager.getSensorList(TYPE_ALL)` at agent-service creation. Each present sensor is advertised with its Android type, protocol ID, vendor, version, range, resolution, power, delays, reporting mode, wake-up state, and FIFO capacity. Unknown OEM sensor types receive `sensor.android_<type>` IDs rather than fabricated mappings.

Start/stop commands use a shared listener controller that prevents duplicate registration. Presets limit outbound updates; even fastest-safe is capped at 50 samples/second. The UI retains at most 180 samples per sensor and renders up to three traces.

Physical test: connect the phone, open Sensors, confirm inventory matches a local sensor-inspection app, start accelerometer and gyroscope, move/rotate the phone, stop both, then disconnect and confirm listeners are removed when the service ends.
