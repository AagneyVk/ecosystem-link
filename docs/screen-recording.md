# Standard screen recording

`screen.record` uses Android MediaProjection and advertises `consent_required`; consent is never treated as durable. A hub command opens the Android system capture dialog. Approval starts a `mediaProjection` foreground service, H.264/MP4 recording, a fresh virtual display, and a visible ongoing notification. Recording stops at the requested duration, explicit stop, projection revocation, or service failure.

The temporary MP4 is uploaded through the existing session-bound SHA-256 transfer. It is deleted only when the hub returns successful verification. Android 14+ therefore receives a new consent result for every recording and no projection token is reused. Protected/secure surfaces remain unavailable by platform design.

Physical test: choose Screen, start a 15-second balanced recording, approve the phone dialog, confirm the foreground notification, stop or wait, then verify the MP4 appears and plays in the Screen page. Repeat after denying consent and confirm no recording appears.
