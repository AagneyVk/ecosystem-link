# Permissions and restrictions

Capability advertisements include `permission_state`, availability, restriction reason, provider, operations, and metadata. Current states include `granted`, `denied`, `not_requested`, `foreground_only`, and `consent_required`.

- Camera: requested through the existing companion flow; foreground lifecycle may be required.
- Microphone: requested contextually; recording uses a microphone foreground service.
- Location: coarse/fine Android runtime permission; coarse is reported distinctly.
- Notifications: required for reliable foreground/user-action prompts on recent Android.
- MediaProjection: per-session system consent, not a reusable permission.

Do not grant permissions unrelated to the feature being tested.
