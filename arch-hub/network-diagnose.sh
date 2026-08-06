#!/usr/bin/env bash
set -u

CONFIG="${1:-$HOME/.config/ecosystem/config.toml}"
echo "== Ecosystem / ZeroTier network diagnostic =="
echo "Config: $CONFIG"

echo
echo "-- VPN interfaces --"
ip -br -4 addr show 2>/dev/null | awk '$1 ~ /^(zt|tailscale|wg|tun)/ {print}' || true
if command -v zerotier-cli >/dev/null 2>&1; then
    echo
    echo "-- ZeroTier status --"
    zerotier-cli info || true
    zerotier-cli listnetworks || true
fi

if [[ ! -s "$CONFIG" ]]; then
    echo "ERROR: missing or empty config: $CONFIG"
    exit 1
fi

readarray -t VALUES < <(python3 - "$CONFIG" <<'PY'
import sys, tomllib
with open(sys.argv[1], "rb") as f:
    c = tomllib.load(f)
for key in ("ws_host", "ws_port", "transfer_host", "transfer_port", "ui_host", "ui_port", "streaming_host", "streaming_port"):
    print(c.get(key, ""))
print("enabled" if c.get("shared_secret") else "disabled")
PY
)
WS_HOST="${VALUES[0]}"; WS_PORT="${VALUES[1]}"
TRANSFER_HOST="${VALUES[2]}"; TRANSFER_PORT="${VALUES[3]}"
UI_HOST="${VALUES[4]}"; UI_PORT="${VALUES[5]}"
STREAM_HOST="${VALUES[6]}"; STREAM_PORT="${VALUES[7]}"
AUTH_STATE="${VALUES[8]}"

echo
echo "-- Configured endpoints --"
echo "WebSocket : ws://$WS_HOST:$WS_PORT"
echo "Transfer  : http://$TRANSFER_HOST:$TRANSFER_PORT"
echo "UI        : http://$UI_HOST:$UI_PORT"
echo "Streaming : ws://$TRANSFER_HOST:$TRANSFER_PORT/stream (shared transfer port)"
echo "Handshake authentication: $AUTH_STATE"
if [[ "$AUTH_STATE" == "enabled" ]]; then
    echo "ERROR: current Android v1 uses the no-secret handshake; set shared_secret = \"\" in the hub config"
fi

echo
echo "-- Address ownership --"
for host in "$WS_HOST" "$TRANSFER_HOST"; do
    if ip -o -4 addr show | grep -Fq " $host/"; then
        echo "OK: $host belongs to this Arch machine"
    else
        echo "ERROR: $host is not assigned to a local interface"
    fi
done

echo
echo "-- Listening sockets --"
ss -lntp | grep -E ":($WS_PORT|$TRANSFER_PORT|$UI_PORT)( |$)" || echo "ERROR: expected hub listeners not found"

echo
echo "-- Service --"
systemctl --user --no-pager --full status ecosystem.service 2>&1 | tail -n 20

echo
echo "-- Recent hub logs --"
journalctl --user -u ecosystem.service -n 40 --no-pager 2>&1
