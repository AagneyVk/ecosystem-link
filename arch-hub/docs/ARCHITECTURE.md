# Ecosystem Hub — Architecture

## Purpose

The hub is the Linux-side half of a personal device ecosystem. It is the
central automation engine, storage node, and command dispatcher for one or
more companion devices (Version 1: a single Android phone).

## Layers

```
┌─────────────────────────────────────────────────────────┐
│  main.py — composition root, wires everything together  │
├─────────────────────────────────────────────────────────┤
│  server/websocket_server.py   — control plane (commands, │
│                                   sessions, state)        │
│  transfer/file_transfer_server.py — data plane (binary    │
│                                   artifact upload, HTTP)   │
├─────────────────────────────────────────────────────────┤
│  core/dispatcher.py  — routes commands to plugins         │
│  plugins/*            — one plugin per capability category│
├─────────────────────────────────────────────────────────┤
│  core/session_manager.py       — session lifecycle        │
│  core/capability_registry.py   — per-device capability set│
│  core/device_manager.py        — connection + runtime state│
│  storage/storage_manager.py    — unified artifact hierarchy│
└─────────────────────────────────────────────────────────┘
```

## Why two transports (WebSocket + HTTP)?

The command/control channel (WebSocket) needs to stay low-latency and
responsive — status queries, session start/stop, small JSON payloads.
Binary media transfer is bursty and can be large. Mixing the two on one
socket means a multi-megabyte photo upload can head-of-line-block a
`get_active_sessions` query. Keeping them as separate services, on
separate ports, means the control plane never contends with big transfers,
and either can be scaled or hardened independently later (e.g. a future
version could put the HTTP transfer endpoint behind mTLS while the control
socket stays as-is).

## Why plugins?

Version 1 only implements camera and microphone, but the spec explicitly
calls for clipboard sync, notification forwarding, file handoff, presence,
media controls, and automation rules later. If capability handling were
hardcoded into the WebSocket server, every new capability would mean
touching transport code. Instead:

- A capability is just a dotted string the Android agent advertises
  (`"camera.snapshot"`, and later `"clipboard.read"`, etc.) — no protocol
  change needed to add one.
- A `HubPlugin` subclass claims a set of command names and implements
  `handle_command`. It receives a `PluginContext` with everything it needs
  (session manager, capability registry, device manager, storage,
  a `send_to_device` function) via dependency injection — nothing reaches
  into globals, so plugins are independently unit-testable with fakes (see
  `tests/test_camera_plugin.py`).
- Adding a future `ClipboardPlugin` is one class + one line in `main.py`
  (`dispatcher.register(ClipboardPlugin(plugin_ctx))`).

## Session model

Long-running or even short async operations (a photo capture, an audio
recording, a live stream) are modeled uniformly as `Session` objects with
state `pending → running → completed | failed | cancelled`. This gives:

- A single place (`SessionManager`) that always knows what's active,
  satisfying the "Linux should always know which sessions are active"
  requirement.
- Automatic failure recovery semantics: if a device disconnects mid-session,
  `fail_all_for_device()` transitions every active session for that device
  to `FAILED` with a structured reason, rather than leaving orphaned state.
- `on_failure` hooks so future automation ("notify me if a stream drops")
  can be layered on without touching the session machinery itself.

## File transfer & storage guarantees

1. Android captures to a local temp file and computes SHA-256.
2. It `PUT`s the bytes to `/upload/<device_id>/<session_id>/<filename>`
   with the checksum in a header.
3. The hub recomputes the checksum server-side. Mismatch → `409`, artifact
   is **not** stored, and the response explicitly tells the agent not to
   delete its local copy.
4. On match, the artifact is written under
   `<storage_root>/<device_id>/<category>/<YYYY>/<MM>/<DD>/<filename>`,
   sidecar metadata (session info, checksum, size) is written to
   `<storage_root>/<device_id>/metadata/<session_id>.json`, the session is
   marked `COMPLETED`, and the hub responds `200` with
   `safe_to_delete_local_copy: true`.
5. Only after that `200` does the agent delete its temp file. The phone is
   a temporary sensor node, never the durable copy.

The `device_id` prefix in every storage path means a second companion
device can join later without any migration of existing data.

## Security model

- The system assumes both devices are owned/trusted by the same person and
  relies on the existing VPN mesh for transport-level trust (per the
  networking model — NAT traversal and public routing are explicitly out
  of scope).
- A pre-shared secret is exchanged at handshake as a second factor, so a
  stray process on the VPN can't silently impersonate a device.
- Every capability carries its own `permission_granted` flag reported by
  the agent; the hub refuses to dispatch a command for a capability the
  device hasn't both advertised *and* granted permission for.
- Sensitive capability categories (camera, microphone, location) are
  handled by dedicated plugins so a future per-capability revocation
  mechanism has an obvious place to live (deny at the plugin's
  `handle_command` entry point).

## Extending to a new capability (example: future clipboard sync)

1. Android agent adds `"clipboard.read"` / `"clipboard.write"` to its
   advertised capability list.
2. Add `CommandName.CLIPBOARD_READ` / `WRITE` to `core/protocol.py`.
3. Write `plugins/clipboard_plugin.py` implementing `HubPlugin`.
4. Register it in `main.py`.
5. No changes needed to `websocket_server.py`, `dispatcher.py`,
   `session_manager.py`, or the storage layer.

## Testing strategy

- `core/` and `plugins/` are pure-async classes with constructor-injected
  dependencies — no global state, no live sockets required. Plugin tests
  build a `PluginContext` with an in-memory `SessionManager` and a fake
  `send_to_device` recorder (see `tests/test_camera_plugin.py`).
- `SessionManager` lifecycle and failure-recovery behavior is tested
  directly (`tests/test_session_manager.py`).
- Integration/end-to-end testing against a real Android agent should use a
  mock agent script (not included in V1) that speaks the handshake +
  command protocol over a local WebSocket for CI purposes.
