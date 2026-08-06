# Ecosystem Agent (Android) — Architecture

## Purpose

The Android agent is the sensor/context/remote-hardware-interface half of
the ecosystem. It runs as a persistent foreground service, connects to the
Linux hub over the VPN mesh, advertises its capabilities, and executes
commands the hub sends it.

## Component map

```
EcosystemApplication          — starts the service on process launch if configured
  └─ EcosystemAgentService    — the persistent "daemon"; owns the WS client
       ├─ EcosystemWebSocketClient  — control-plane connection, reconnect/backoff
       ├─ CapabilityRegistry        — camera / microphone / device.state, extensible
       │    ├─ CameraCapability     — snapshot; foreground-gated
       │    ├─ MicrophoneCapability — bounded record + streaming sessions
       │    └─ SensorStateCapability— battery/charging/network (always-on)
       ├─ SessionManager            — mirrors hub session lifecycle locally
       ├─ RuntimeStateReporter      — VPN/permission/service/restriction snapshot
       └─ FileTransferClient        — HTTP upload of captured artifacts

CompanionActivity              — setup UI + foreground surface for camera gate
MicrophoneForegroundService    — dedicated FGS satisfying mic-capture policy
BootReceiver                   — optional auto-restart after reboot
```

## Why a foreground service, not a background job

The runtime requirements are explicit: the agent must keep running with
the screen off and the device locked, survive background restrictions,
and expose live state. A plain background service would be killed by
Android's process management within minutes on most OEM skins. A
persistent foreground service (with a low-importance, unobtrusive
notification) is the only supported way to keep a long-lived socket open
and respond to hub commands promptly. `EcosystemAgentService` is the
single owner of that lifecycle; every capability is a plain object it
constructs and injects with what it needs (`Context`, `SessionManager`,
`FileTransferClient`, etc.) — no capability starts its own long-running
service except where Android specifically mandates a distinct foreground
service type (microphone).

## Why capabilities are separate from the WebSocket client

`EcosystemWebSocketClient` only knows how to route a `command` to
`CapabilityRegistry.ownerOf(command)` and translate the `CapabilityResult`
back into a `response`/`error` envelope. It has zero camera- or
microphone-specific code. Adding a future capability (e.g. clipboard read)
means:

1. Implement `Capability` (or `StreamingCapability` for session-based ones).
2. Register it in `EcosystemAgentService.onCreate()`.
3. Add its command names to `net/Protocol.kt`'s `CommandName` object (kept
   in sync with the hub's `protocol.py`).

No changes to the WebSocket transport, session manager, or transfer client
are needed.

## Foreground-gated capabilities (camera)

Camera access needs a resumed, visible Activity to reliably bind CameraX
use cases. `ForegroundGate` abstracts "is the app currently visible" and
"bring it to the front" so `CameraCapability` never touches `Activity`
directly (making it unit-testable with a fake gate). The flow specified in
the runtime requirements doc is implemented literally:

1. `CameraCapability.takePhoto()` checks `foregroundGate.isAppForegrounded()`.
2. If false, it calls `requestForeground()` (launches `CompanionActivity`
   with `FLAG_ACTIVITY_REORDER_TO_FRONT`, which — combined with
   `showWhenLocked`/`turnScreenOn` in the manifest — wakes the device) and
   immediately returns a structured `CAMERA_FOREGROUND_REQUIRED` error so
   the hub isn't left waiting indefinitely.
3. `CompanionActivity.onResume()` registers itself with the gate.
4. Once foregrounded, capture can proceed and `provider.bindToLifecycle()`
   uses the now-resumed activity as the lifecycle owner.

## Structured failure contract

Every capability failure returns a `CapabilityResult.Failure` with
`errorCode` / `message` / `recommendedAction` / `requiresUserInteraction` /
`missingPermission`, matching the hub's `ErrorCode` enum and the exact
`CAMERA_FOREGROUND_REQUIRED` example from the spec. Nothing is allowed to
fail silently — an unhandled exception in a capability should still
surface as `INTERNAL_ERROR` through the WebSocket client's try/catch, not
vanish.

## Runtime state reporting

`RuntimeStateReporter` builds the snapshot the hub needs: VPN connection
(checked via `ConnectivityManager` transport capabilities), per-capability
permission grants (queried live from each registered `Capability`), active
foreground services, and any currently-active restrictions. This is sent
at handshake and re-pushed every 30s plus on-demand via `state_update`
messages, so the hub's view doesn't go stale between explicit polls.

## Reconnection

`EcosystemWebSocketClient` uses exponential backoff (1s → 30s cap) driven
by a coroutine on the service's `SupervisorJob` scope, entirely
independent of Activity lifecycle. A dropped Wi-Fi/VPN connection results
in automatic reconnect attempts without any user interaction; the hub-side
`fail_all_for_device()` behavior means any sessions that were active get
marked `FAILED` on the hub rather than hanging forever.

## Extensibility for future features

Per the spec, clipboard sync, notification forwarding, presence, and media
controls are explicitly out of Version 1 scope but must not be
architecturally blocked:

- **Clipboard/notifications**: would be new `Capability` implementations
  registered the same way as camera/microphone; notification forwarding
  would additionally need a `NotificationListenerService`, which is a
  separate Android component that can feed events into the same
  `EcosystemWebSocketClient.send()` path via an `EVENT` message type
  (already defined in `MessageType`).
- **Presence detection**: fits naturally as a periodic addition to
  `RuntimeStateReporter`'s snapshot (e.g. "is the phone screen on", "is it
  in the user's pocket via proximity sensor").
- **File handoff**: reuses `FileTransferClient` as-is, since it's already
  general-purpose (takes a `File` + session type, not camera/audio-specific).

## Testing strategy

`Capability`, `SessionManager`, and `CapabilityRegistry` have no direct
Android framework dependencies at the interface level, so they're covered
by plain JUnit tests under `app/src/test/` (`CapabilityRegistryTest`,
`SessionManagerTest`) using a `FakeCapability` rather than a real
Context-dependent implementation. `CameraCapability`/`MicrophoneCapability`
depend on `Context` and Android APIs and are intended for
instrumented/androidTest coverage (not included in V1, since Version 1
prioritizes the extensible skeleton — see the "Known limitations" section
of the top-level README for what to add next).
