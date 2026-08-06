# Ecosystem Link

Ecosystem Link is a self-hosted bridge between an Android phone and an Arch Linux workstation. The Android agent exposes user-approved device capabilities; the Python hub provides a browser dashboard, session management, media storage, file transfer, and live-stream relaying over a trusted VPN.

> [!WARNING]
> This is an experimental personal project, not a hardened remote-administration product. Run it only on devices and networks you control. The default transport is cleartext HTTP/WebSocket and must be protected by a trusted VPN and host firewall.

## Features

- Resilient Android-to-hub WebSocket connection with versioned messages and reconnect handling
- Camera snapshots and experimental live camera relay
- Microphone recordings and audio-session support
- MediaProjection screen recording and experimental live screen relay
- Physical sensor and location sessions
- Bidirectional clipboard text and file transfers
- Browser UI for devices, media, sensors, location, files, clipboard, and transfers
- Session-bound uploads with SHA-256 verification and path confinement
- Arch Linux user-level systemd installation

Android always remains inside the normal, non-root application sandbox. Camera, microphone, location, notifications, and screen capture require Android permissions or per-session system consent. Secure/protected surfaces cannot be captured.

## Repository layout

```text
android-agent/  Kotlin Android application (API 26+, target API 35)
arch-hub/       Python 3.11+ hub, web UI, control and transfer services
docs/           Protocol, permissions, feature, testing, and audit notes
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the end-to-end design.

## Quick start

### 1. Install the hub on Arch Linux

```bash
sudo pacman -S --needed python rsync
git clone https://github.com/AagneyVk/ecosystem-link.git
cd ecosystem-link/arch-hub
chmod +x install.sh
ECOSYSTEM_VPN_IP=<arch-vpn-ip> ./install.sh
systemctl --user enable --now ecosystem.service
journalctl --user -u ecosystem.service -f
```

The installer creates `~/.config/ecosystem/config.toml`. Review its bind addresses, then open the UI at `http://127.0.0.1:8768` on the hub. If the hub should run while you are logged out, run `sudo loginctl enable-linger "$USER"` once.

### 2. Build and install the Android agent

Open `android-agent/` in Android Studio with JDK 17, or build from a terminal:

```bash
cd android-agent
./gradlew assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on your phone. Enter the hub VPN endpoints in the app:

```text
Control:  ws://<arch-vpn-ip>:8765
Transfer: http://<arch-vpn-ip>:8766
```

Grant only the Android permissions needed for the features you choose. Screen capture approval is intentionally requested by Android for each MediaProjection session.

## Development

Hub tests:

```bash
cd arch-hub
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest -q
```

Android checks:

```bash
cd android-agent
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Network diagnostics on Arch:

```bash
cd arch-hub
./network-diagnose.sh
python diagnose.py --config ~/.config/ecosystem/config.toml
```

## Security and privacy

- Do not expose ports 8765-8768 directly to the public internet.
- Bind phone-facing services to the VPN address and restrict them with a firewall.
- Keep real `config.toml`, `.env`, Android `local.properties`, signing keys, recordings, and received files out of Git.
- Treat remote camera, microphone, location, sensor, screen, clipboard, and file access as sensitive.
- Review [SECURITY.md](SECURITY.md) before deployment.

## Status

The control, transfer, UI, and automated test foundations are working. Live camera, live screen, audio behavior, Android background restrictions, and OEM-specific permission flows still require physical-device testing. See [V2_CHECKLIST.md](V2_CHECKLIST.md) for the implementation roadmap.

## License

Released under the [MIT License](LICENSE).

