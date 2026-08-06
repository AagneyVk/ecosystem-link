from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

try:
    import tomllib  # py3.11+
except ImportError:  # pragma: no cover
    import tomli as tomllib  # type: ignore

DEFAULT_CONFIG_PATHS = [
    Path.home() / ".config" / "ecosystem" / "config.toml",
    # Compatibility with the original installer. New installs use the
    # canonical path above, but upgrades must not silently ignore this file.
    Path.home() / ".config" / "ecosystem-hub" / "config.toml",
    Path("/etc/ecosystem/config.toml"),
]

DEFAULTS = {
    "storage_root": str(Path.home() / ".local" / "share" / "ecosystem"),
    "ws_host": "127.0.0.1",
    "ws_port": 8765,
    "transfer_host": "127.0.0.1",
    "transfer_port": 8766,
    "admin_host": "127.0.0.1",
    "admin_port": 8767,
    "ui_host": "127.0.0.1",
    "ui_port": 8768,
    "streaming_host": "127.0.0.1",
    "streaming_port": 8769,
    "shared_secret": "",
    "log_level": "INFO",
    "session_retention_seconds": 3600,
}


@dataclass
class HubConfig:
    storage_root: str
    ws_host: str
    ws_port: int
    transfer_host: str
    transfer_port: int
    admin_host: str
    admin_port: int
    ui_host: str
    ui_port: int
    streaming_host: str
    streaming_port: int
    shared_secret: str
    log_level: str
    session_retention_seconds: int

    @staticmethod
    def load(explicit_path: str | None = None) -> "HubConfig":
        data = dict(DEFAULTS)

        candidates = [Path(explicit_path)] if explicit_path else DEFAULT_CONFIG_PATHS
        for path in candidates:
            if path.exists() and path.stat().st_size > 0:
                with open(path, "rb") as f:
                    data.update(tomllib.load(f))
                break

        # Environment variables override file config, e.g. for systemd overrides.
        env_map = {
            "ECOSYSTEM_STORAGE_ROOT": "storage_root",
            "ECOSYSTEM_WS_HOST": "ws_host",
            "ECOSYSTEM_WS_PORT": "ws_port",
            "ECOSYSTEM_TRANSFER_HOST": "transfer_host",
            "ECOSYSTEM_TRANSFER_PORT": "transfer_port",
            "ECOSYSTEM_ADMIN_HOST": "admin_host",
            "ECOSYSTEM_ADMIN_PORT": "admin_port",
            "ECOSYSTEM_UI_HOST": "ui_host",
            "ECOSYSTEM_UI_PORT": "ui_port",
            "ECOSYSTEM_STREAMING_HOST": "streaming_host",
            "ECOSYSTEM_STREAMING_PORT": "streaming_port",
            "ECOSYSTEM_SHARED_SECRET": "shared_secret",
            "ECOSYSTEM_LOG_LEVEL": "log_level",
        }
        int_keys = {"ws_port", "transfer_port", "admin_port", "ui_port",
                    "streaming_port", "session_retention_seconds"}
        for env_var, key in env_map.items():
            if env_var in os.environ:
                value = os.environ[env_var]
                if key in int_keys:
                    value = int(value)
                data[key] = value

        return HubConfig(**data)
