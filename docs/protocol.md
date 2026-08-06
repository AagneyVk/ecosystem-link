# Control protocol

The implemented wire protocol is JSON envelope V1 (`proto_version: 1`). Required fields are `msg_id`, `type`, `timestamp`, and object `payload`; `device_id`, `in_reply_to`, and `correlation_id` are optional. During migration, readers derive `correlation_id` from legacy `in_reply_to`. Unsupported versions and duplicate inbound message IDs are rejected/ignored respectively.

Implemented message types are `handshake`, `handshake_ack`, `command`, `response`, `event`, `session_control`, `session_event`, `state_update`, `error`, `ping`, and `pong`. This is not yet the complete V2 taxonomy. Media bytes are never placed in JSON.

The Android and Python models are manually mirrored. Compatibility fixtures remain required before claiming V2 interoperability.
