# Architecture

## System overview

Ecosystem Link has two trusted endpoints connected by a user-provided private VPN.

```text
Android agent
  permissions + foreground services
  capability registry
  control client / transfer client / stream producer
             |
             | VPN: JSON control + HTTP files + binary WebSocket streams
             v
Arch hub
  WebSocket control :8765
  transfer + stream :8766
  local admin API   :8767
  browser UI        :8768
  sessions / storage / media index / plugins
             |
             v
Browser dashboard + user-owned storage
```

## Android agent

`EcosystemAgentService` owns connection lifecycle and capability dispatch. `EcosystemWebSocketClient` performs the handshake, routes versioned envelopes, and reconnects with backoff. Capabilities encapsulate camera, microphone, location, sensors, clipboard, files, and screen capture. Long-running or privacy-sensitive work uses Android foreground services and visible notifications.

MediaProjection consent is handled by `ScreenCaptureActivity`; recording and live capture run in separate services. Android platform permission and secure-surface boundaries are not bypassed. Preferences, including any shared secret, use application-private storage and are never part of the repository.

## Arch hub

`ecosystem_hub.main` is the composition root. It wires the device and capability registries, dispatcher, sessions, jobs, storage, media index, servers, and feature plugins.

- The control plane exchanges JSON protocol envelopes on port 8765.
- File transfer and binary stream relay share one aiohttp application on port 8766.
- The local admin service listens on port 8767.
- The dependency-free browser UI and its event channel listen on port 8768.
- Plugins translate UI commands into device operations and session transitions.
- Storage validates ownership, confines paths, verifies hashes, and commits uploads atomically.

## Protocol and sessions

Every control message uses protocol version 1 and has a message ID, type, timestamp, and object payload. Correlation IDs connect requests and responses. The handshake advertises device identity, capabilities, permissions, and runtime state. Unsupported versions and duplicate message IDs are rejected.

Bounded work and live streams use explicit session IDs. Session state is mirrored between endpoints so stop, failure, reconnect, and cleanup behavior can be reasoned about independently of the transport connection.

## Trust boundaries

The VPN protects transport confidentiality for the current cleartext endpoints; it does not replace application authorization. The browser/admin endpoints should remain local. Phone-facing listeners must bind to a specific VPN address and be firewalled. Inputs crossing the device boundary are untrusted: paths are sanitized, uploads are hashed, JSON is validated, and generated media is stored outside the source tree.

See `docs/` for feature-specific design notes and protocol details.

