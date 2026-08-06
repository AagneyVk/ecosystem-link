# Arch UI

The aiohttp/browser architecture is retained. `app.js` now contains one observable store and one `HubAPI` client; pages do not open their own sockets. Persistent navigation covers Overview, Camera, Audio, Screen, Sensors, Location, Files, Clipboard, Transfers, and Settings. Controls are enabled from advertised capability state, and unavailable features render their reported reason.

The UI is served at the configured `ui_host:ui_port` (default `http://127.0.0.1:8768`). It uses no external web assets or frontend build step. Test resizing at 1366×768 and a narrow mobile-width browser viewport.
