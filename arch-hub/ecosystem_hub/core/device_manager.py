"""
Device manager.

Tracks connected devices, their live WebSocket connection, and their most
recently reported runtime state (VPN status, permission grants, active
foreground services, restrictions). Built multi-device-ready from the start
since the vision explicitly calls for more than one companion device
eventually.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Optional

logger = logging.getLogger("ecosystem_hub.device")


@dataclass
class RuntimeState:
    vpn_connected: Optional[bool] = None
    agent_connected: bool = False
    permissions: dict[str, bool] = field(default_factory=dict)
    active_services: dict[str, str] = field(default_factory=dict)  # name -> "running"/"stopped"
    restrictions: list[str] = field(default_factory=list)
    last_updated: float = field(default_factory=time.time)

    def update(self, payload: dict[str, Any]) -> None:
        if "vpn_connected" in payload:
            self.vpn_connected = payload["vpn_connected"]
        if "permissions" in payload:
            self.permissions.update(payload["permissions"])
        if "active_services" in payload:
            self.active_services.update(payload["active_services"])
        if "restrictions" in payload:
            self.restrictions = payload["restrictions"]
        self.last_updated = time.time()

    def to_dict(self) -> dict:
        return {
            "vpn_connected": self.vpn_connected,
            "agent_connected": self.agent_connected,
            "permissions": self.permissions,
            "active_services": self.active_services,
            "restrictions": self.restrictions,
            "last_updated": self.last_updated,
        }


class Device:
    def __init__(self, device_id: str, display_name: str = ""):
        self.device_id = device_id
        self.display_name = display_name or device_id
        self.connection: Any = None  # set to the ws connection handler when connected
        self.runtime_state = RuntimeState()
        self.connected_at: Optional[float] = None

    @property
    def is_connected(self) -> bool:
        return self.connection is not None


class DeviceManager:
    def __init__(self):
        self._devices: dict[str, Device] = {}

    def get_or_create(self, device_id: str, display_name: str = "") -> Device:
        if device_id not in self._devices:
            self._devices[device_id] = Device(device_id, display_name)
        return self._devices[device_id]

    def get(self, device_id: str) -> Optional[Device]:
        return self._devices.get(device_id)

    def mark_connected(self, device_id: str, connection: Any) -> Device:
        device = self.get_or_create(device_id)
        device.connection = connection
        device.connected_at = time.time()
        device.runtime_state.agent_connected = True
        logger.info("device connected: %s", device_id)
        return device

    def mark_disconnected(self, device_id: str) -> None:
        device = self._devices.get(device_id)
        if device:
            device.connection = None
            device.runtime_state.agent_connected = False
            logger.info("device disconnected: %s", device_id)

    def connected_devices(self) -> list[Device]:
        return [d for d in self._devices.values() if d.is_connected]

    def all_devices(self) -> list[Device]:
        return list(self._devices.values())
