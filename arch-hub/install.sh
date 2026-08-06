#!/usr/bin/env bash
#
# Ecosystem Hub installer for Arch Linux.
#
# Installs the hub as a systemd --user service running under the invoking
# user's account (not root - this daemon should never need root, it only
# talks to the network and writes to a storage directory it owns).
#
set -euo pipefail

INSTALL_DIR="${ECOSYSTEM_INSTALL_DIR:-$HOME/.local/share/ecosystem-hub}"
CONFIG_DIR="$HOME/.config/ecosystem"
CONFIG_PATH="$CONFIG_DIR/config.toml"
VENV_DIR="$INSTALL_DIR/venv"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"

echo "==> Ecosystem Hub installer"
echo "    install dir : $INSTALL_DIR"
echo "    config dir  : $CONFIG_DIR"

if [[ $EUID -eq 0 ]]; then
    echo "ERROR: do not run this installer as root. It installs a user-level service." >&2
    exit 1
fi

command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 not found. Install with: sudo pacman -S python" >&2; exit 1; }
command -v rsync >/dev/null 2>&1 || { echo "ERROR: rsync not found. Install with: sudo pacman -S rsync" >&2; exit 1; }

PY_VERSION=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
REQUIRED="3.11"
if [[ "$(printf '%s\n' "$REQUIRED" "$PY_VERSION" | sort -V | head -n1)" != "$REQUIRED" ]]; then
    echo "ERROR: Python >= $REQUIRED required, found $PY_VERSION" >&2
    exit 1
fi

echo "==> Creating directories"
mkdir -p "$INSTALL_DIR" "$CONFIG_DIR" "$SYSTEMD_USER_DIR"

echo "==> Copying source"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rsync -a --delete \
    --exclude 'venv' --exclude '__pycache__' --exclude '.pytest_cache' --exclude 'tests' \
    "$SCRIPT_DIR/ecosystem_hub" "$INSTALL_DIR/"
cp "$SCRIPT_DIR/requirements.txt" "$INSTALL_DIR/"

echo "==> Creating virtualenv"
python3 -m venv "$VENV_DIR"
"$VENV_DIR/bin/pip" install --upgrade pip -q
"$VENV_DIR/bin/pip" install -q -r "$INSTALL_DIR/requirements.txt"

VPN_IP="${ECOSYSTEM_VPN_IP:-}"
if [[ -z "$VPN_IP" ]] && command -v tailscale >/dev/null 2>&1; then
    VPN_IP="$(tailscale ip -4 2>/dev/null | head -n1 || true)"
fi
if [[ -z "$VPN_IP" ]]; then
    VPN_IP="$(ip -o -4 addr show 2>/dev/null | awk '$2 ~ /^(tailscale|wg|tun|zt)/ {split($4,a,"/"); print a[1]; exit}')"
fi

if [[ ! -f "$CONFIG_PATH" ]]; then
    if [[ -z "$VPN_IP" ]]; then
        echo "ERROR: Could not detect a VPN IPv4 address." >&2
        echo "Re-run with: ECOSYSTEM_VPN_IP=<arch-vpn-ip> ./install.sh" >&2
        exit 1
    fi
    echo "==> Writing config to $CONFIG_PATH (VPN bind: $VPN_IP)"
    sed \
        -e "s#^ws_host = .*#ws_host = \"$VPN_IP\"#" \
        -e "s#^transfer_host = .*#transfer_host = \"$VPN_IP\"#" \
        -e "s#^streaming_host = .*#streaming_host = \"$VPN_IP\"#" \
        "$SCRIPT_DIR/config/config.example.toml" > "$CONFIG_PATH"
else
    echo "==> Existing config found at $CONFIG_PATH, leaving untouched"
    CONFIGURED_WS="$(sed -n 's/^ws_host = "\([^"]*\)"/\1/p' "$CONFIG_PATH" | head -n1)"
    if [[ -n "${VPN_IP:-}" && "$CONFIGURED_WS" != "$VPN_IP" ]]; then
        echo "WARNING: config ws_host is '$CONFIGURED_WS' but detected VPN address is '$VPN_IP'." >&2
        echo "         Edit $CONFIG_PATH or re-run with ECOSYSTEM_VPN_IP=$VPN_IP after moving the old config." >&2
    fi
    if grep -Eq '^shared_secret = "[^" ]+"' "$CONFIG_PATH"; then
        echo "WARNING: shared_secret is enabled, but the current Android v1 agent uses the simplified no-secret handshake." >&2
        echo "         Set shared_secret = \"\" on the hub, then restart the service." >&2
    fi
fi

echo "==> Installing systemd --user unit"
sed "s#__VENV_DIR__#$VENV_DIR#g; s#__INSTALL_DIR__#$INSTALL_DIR#g; s#__CONFIG_PATH__#$CONFIG_PATH#g" \
    "$SCRIPT_DIR/systemd/ecosystem.service.template" > "$SYSTEMD_USER_DIR/ecosystem.service"

systemctl --user daemon-reload

echo ""
echo "==> Install complete."
echo ""
echo "Next steps:"
echo "  1. Review/edit config:  $CONFIG_PATH"
echo "     Android WebSocket:   ws://<vpn-ip>:8765"
echo "     Android transfer:    http://<vpn-ip>:8766"
echo "  2. Enable + start:      systemctl --user enable --now ecosystem.service"
echo "  3. Check status:        systemctl --user status ecosystem.service"
echo "  4. Follow logs:         journalctl --user -u ecosystem.service -f"
echo ""
echo "  If you want the hub running even when you're not logged in, run:"
echo "      sudo loginctl enable-linger \$USER"
echo ""
