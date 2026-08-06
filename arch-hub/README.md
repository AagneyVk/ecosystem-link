# Ecosystem Hub (Arch Linux)

Linux-side component of a personal device ecosystem. Acts as the central
hub, automation engine, and storage node for companion devices (Version 1:
one Android phone). See `docs/ARCHITECTURE.md` for the full design
rationale.

## Prerequisites

- Arch Linux (or any systemd Linux distro — nothing here is Arch-specific
  beyond the installer assuming `pacman` is available for the Python
  package hint).
- Python >= 3.11 (`sudo pacman -S python`)
- `rsync` (`sudo pacman -S rsync`)
- A working mesh/VPN connection to the Android device already established
  (Tailscale, WireGuard, etc. — this project does not set that up).

## Install

```bash
git clone <this-repo> arch-hub
cd arch-hub
chmod +x install.sh
./install.sh
```

This will:

- Create a virtualenv under `~/.local/share/ecosystem-hub/venv`
- Copy source to `~/.local/share/ecosystem-hub`
- Generate `~/.config/ecosystem/config.toml`, binding phone-facing services
  to the detected Tailscale/WireGuard VPN address
- Install a **systemd --user** service (runs as your user, not root)

## Start the service

```bash
systemctl --user enable --now ecosystem.service
systemctl --user status ecosystem.service
journalctl --user -u ecosystem.service -f
```

If you want the hub to run even when you aren't logged in (e.g. it's a
headless box), also run:

```bash
sudo loginctl enable-linger $USER
```

## Configuration

Edit `~/.config/ecosystem/config.toml`:

| Key | Meaning |
|---|---|
| `storage_root` | Where captured artifacts are stored |
| `ws_port` | WebSocket control-plane port (default 8765) |
| `transfer_port` | HTTP file-transfer port (default 8766) |
| `shared_secret` | Pre-shared token the Android agent must present |
| `log_level` | `DEBUG`/`INFO`/`WARNING`/`ERROR` |
| `session_retention_seconds` | How long completed/failed sessions stay queryable before pruning |

After editing, restart: `systemctl --user restart ecosystem.service`

## Manual run (development)

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements-dev.txt
python -m ecosystem_hub.main --config ./config/config.example.toml
```

## Running tests

```bash
pip install -r requirements-dev.txt
pytest tests/ -v
```

## Firewall note

The hub listens on `ws_port` (8765) and `transfer_port` (8766) on all
interfaces by default. Since the networking model assumes a VPN mesh,
restrict these with your firewall to only the VPN interface, e.g. with
`ufw`:

```bash
sudo ufw allow in on tailscale0 to any port 8765,8766 proto tcp
```

## Directory layout

```
ecosystem_hub/
  core/        protocol, session/device/capability managers, dispatcher
  server/      WebSocket control-plane server
  transfer/    HTTP file-transfer server
  storage/     unified artifact storage on disk
  plugins/     one file per capability category (camera, audio, ...)
  config/      config loading + example config
tests/         pytest suite (mock-based, no live sockets required)
systemd/       service unit template
install.sh     installer
docs/          architecture documentation
```
