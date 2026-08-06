# Ecosystem V2 implementation checklist

Updated: 2026-07-29

## Stage 1 — audit and stabilise

- [x] Inventory Android and Arch source trees, frameworks, build files, and entry points.
- [x] Trace the current camera snapshot control and transfer path.
- [x] Attempt baseline builds/tests and record environment blockers.
- [x] Add protocol-envelope and snapshot-adjacent storage regression tests.
- [x] Confine peer-provided path components and atomically commit artifacts.
- [x] Run hub tests: 24 passed with project-local Python 3.12 virtual environment.
- [x] Run Android tests/build: 7 passed; Gradle wrapper build produced the debug APK.

## Stage 2 — protocol and state

- [x] Preserve V1 envelopes and add an explicit correlation-ID migration field.
- [x] Reject unsupported versions and duplicate inbound message IDs.
- [ ] Add the complete V2 message taxonomy, job manager, transfer state manager, and cross-platform fixtures.
- [ ] Add authenticated transfer and stream tickets before remote deployment.

## Stages 3–9

- [ ] UI foundation and capability-driven navigation.
- [ ] Camera controls, gallery, burst/video, and production live transport.
- [ ] Audio recording/playback/live transport.
- [ ] Real SensorManager inventory and bounded streams; location providers and diagnostics.
- [ ] MediaProjection provider and owner-authorised input providers.
- [ ] Two-way files, clipboard, transfer centre.
- [ ] Reconnection/state reconciliation, installers, complete tests, performance pass.

Items are unchecked unless implemented in production code and verified. The existing MJPEG camera stream is experimental and is not represented as WebRTC.

## Current functional milestone (implemented; physical/build verification pending)

- [x] Central browser state store, reusable hub client, responsive persistent navigation, capability-aware overview, and structured error dialog.
- [x] Root-confined media indexing, opaque content/delete APIs, automatic refresh after verified upload, lazy gallery, image viewer, and video playback.
- [x] Rich capability advertisements and finite job lifecycle with snapshot/screen transfer completion.
- [x] Actual Android SensorManager inventory, common protocol mapping, duplicate-safe/downsampled streaming, shutdown cleanup, and bounded browser charts.
- [x] Platform LocationManager current/live requests, provider/permission diagnostics, fresh-fix timeout, staleness fields, and location UI.
- [x] MediaProjection per-session consent activity, foreground service, H.264 MP4 recording, revocation/duration cleanup, verified upload, and screen recordings UI.
- [x] Contextual capability permission/restriction reporting and agent error forwarding.
- [x] Python tests: 24 passed.
- [x] Android tests/APK build: 7 passed; debug APK assembled successfully.
- [ ] Physical phone acceptance tests.
