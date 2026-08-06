# Architecture audit

The workspace contains two applications. `arch-hub` is an asynchronous Python service using aiohttp and websockets, with a dependency-free browser UI. `android-agent` is a Kotlin Android application using Compose, CameraX, OkHttp, coroutines, and kotlinx.serialization. Entry points are `ecosystem_hub.main` and `CompanionActivity`/`EcosystemAgentService`.

The hub separates device/capability registries, command dispatch, plugins, sessions, storage, control WebSocket, HTTP transfer, MJPEG relay, and UI server. Android separates its connection runtime, mirrored protocol envelope, capability registry, session state, foreground-service control, CameraX snapshot/stream, audio recording, transfer client, and companion UI.

## Verified snapshot trace

1. The browser calls the hub UI service with `take_photo`.
2. `CameraPlugin` checks `camera.snapshot`, creates a `camera_snapshot` session, and sends a V1 `command` envelope.
3. Android routes the command to `CameraCapability`, obtains foreground lifecycle access when Android requires it, and captures JPEG with CameraX.
4. Android hashes the file and sends binary bytes by HTTP PUT, outside the control WebSocket.
5. The transfer server validates session ownership and SHA-256; `StorageManager` now sanitises peer path components and atomically renames a `.partial` file after flushing it.
6. The hub writes metadata and completes the session. Android deletes its temporary file only after a successful acknowledgement.

## Current boundary

This repository is an early V1. Camera snapshot, experimental MJPEG camera streaming, M4A microphone recording, device state, sessions, and one-way Android-to-hub transfer exist. GPS, generic physical sensors, MediaProjection, phone input, two-way files, clipboard, jobs, WebRTC, complete transfer negotiation/resume, and the requested media galleries are not implemented.

Remote control and transfer must not be deployed until every remote-facing listener is bound to an explicit VPN address, a shared control secret is configured, and authenticated transfer/stream tickets are implemented.
