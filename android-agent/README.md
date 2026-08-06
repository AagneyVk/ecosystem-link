# Ecosystem Agent (Android)

Android-side component of a personal device ecosystem. Runs as a
persistent foreground service acting as a sensor node and remote hardware
interface for the Arch Linux hub. See `docs/ARCHITECTURE.md` for design
rationale.

## Prerequisites

- Android Studio (Koala 2024.1.1 or newer recommended)
- JDK 17
- An Android device or emulator running API 26 (Android 8.0) or newer
- A working VPN/mesh connection between the phone and the Linux hub
  already configured (e.g. Tailscale) — this project assumes it, per the
  networking model, and does not set it up.

## Opening the project

```bash
# Unzip, then open the android-agent/ directory in Android Studio
# ("Open an existing project").
```

Android Studio will generate the Gradle wrapper jar on first sync if it's
missing (this repo ships `gradle/wrapper/gradle-wrapper.properties`
pointing at Gradle 8.7, but not the wrapper jar itself, since it must be
fetched from `services.gradle.org` which isn't reachable from every build
environment — Android Studio handles this transparently, or run
`gradle wrapper` once if you have a system Gradle install).

## Dependencies

All fetched automatically via Gradle from Google/Maven Central — see
`app/build.gradle.kts` for exact versions:

| Library | Purpose |
|---|---|
| CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`) | Camera snapshot capability |
| OkHttp | WebSocket control channel + HTTP file transfer |
| kotlinx.serialization | Protocol envelope JSON encoding/decoding |
| kotlinx.coroutines | Async capability execution, reconnect loop |
| Jetpack Compose + Material3 | Setup screen / companion activity UI |
| androidx.security (`security-crypto`) | Encrypted storage of the shared secret |
| androidx.work | Reserved for future automation-rule scheduling (unused in V1) |

## First-run setup

1. Build and install the app on your phone.
2. Launch it. The setup screen asks for:
   - **Hub WebSocket URL** — `ws://<hub-vpn-ip>:8765`
   - **Hub transfer URL** — `http://<hub-vpn-ip>:8766`
   - **Shared secret** — printed by the hub's `install.sh` on first run
     (also in `~/.config/ecosystem-hub/config.toml` on the hub)
   - **Display name** — whatever you want the hub to call this device
3. Save. You'll be prompted for Camera, Microphone, and Notification
   permissions — all required for Version 1's capabilities.
4. The agent starts its foreground service immediately and begins
   attempting to connect to the hub.

## Building from the command line

```bash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

## Running unit tests

```bash
./gradlew test
```

Covers `CapabilityRegistry` and `SessionManager` with fakes (see
`app/src/test/`). `CameraCapability`/`MicrophoneCapability` need real
Android APIs and are intended for instrumented tests
(`app/src/androidTest/`, not included in V1).

## Known limitations / explicit V1 scope boundaries

- Camera and microphone **streaming** (continuous frame/audio piping to
  the hub, as opposed to snapshot/bounded-recording) are stubbed with a
  clear `CAPABILITY_NOT_FOUND`-style response — the session lifecycle and
  wire messages for it already exist (`camera_stream_start/stop`), only
  the actual frame pipe is unimplemented, by design (see task scope: V1 is
  camera snapshot + audio recording only).
- Accelerometer/gyroscope/magnetometer streaming is not wired to a
  session; `device.state` currently reports battery/charging only.
  Extending it to a live-streaming session follows the same
  `StreamingCapability` pattern as `MicrophoneCapability`.
- Clipboard, notifications, presence, media controls: intentionally not
  implemented — see `docs/ARCHITECTURE.md`'s extensibility section for how
  each would plug in without touching existing code.
- TLS: the WebSocket/HTTP endpoints are plain `ws://`/`http://`, relying on
  the VPN mesh for transport security, matching the "assume reliable VPN
  connectivity" instruction in the top-level spec.
