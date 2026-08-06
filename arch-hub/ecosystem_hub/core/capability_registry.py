"""
Capability registry.

Capabilities are declared by the Android agent at handshake time as a flat
list of dotted strings, e.g.:

    ["camera.snapshot", "microphone.record", "accelerometer.stream",
     "gps.location"]

The hub does not hardcode the set of possible capabilities. This keeps
Version 1 (camera + audio) from constraining future additions like
"clipboard.read" or "notifications.forward" — those just show up in the
list once the agent implements them, and any plugin that declares interest
in that capability name will be able to use it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class Capability:
    name: str  # e.g. "camera.snapshot"
    permission_granted: Optional[bool] = None  # None = unknown
    available: bool = True
    operations: list[str] = field(default_factory=list)
    permission_state: str = "unknown"
    restriction_reason: Optional[str] = None
    provider: Optional[str] = None
    metadata: dict = field(default_factory=dict)  # e.g. resolution options

    @property
    def category(self) -> str:
        """e.g. 'camera.snapshot' -> 'camera'"""
        return self.name.split(".", 1)[0]


class DeviceCapabilities:
    """Capability set for a single connected device."""

    def __init__(self, device_id: str):
        self.device_id = device_id
        self._capabilities: dict[str, Capability] = {}

    def update_from_list(self, raw: list[dict]) -> None:
        self._capabilities.clear()
        for entry in raw:
            name = entry.get("capability_id") or entry["name"]
            self._capabilities[name] = Capability(
                name=name,
                permission_granted=entry.get("permission_granted"),
                available=entry.get("available", True),
                operations=list(entry.get("operations", [])),
                permission_state=entry.get("permission_state", "granted" if entry.get("permission_granted") else "unknown"),
                restriction_reason=entry.get("restriction_reason"),
                provider=entry.get("provider"),
                metadata=entry.get("metadata", {}),
            )

    def has(self, name: str) -> bool:
        cap = self._capabilities.get(name)
        return bool(cap and cap.available)

    def is_permitted(self, name: str) -> bool:
        cap = self._capabilities.get(name)
        return bool(cap and cap.permission_granted)

    def all(self) -> list[Capability]:
        return list(self._capabilities.values())

    def by_category(self, category: str) -> list[Capability]:
        return [c for c in self._capabilities.values() if c.category == category]

    def to_dict(self) -> dict:
        return {
            "device_id": self.device_id,
            "capabilities": [
                {"name": c.name, "capability_id": c.name, "available": c.available,
                 "operations": c.operations, "permission_granted": c.permission_granted,
                 "permission_state": c.permission_state, "restriction_reason": c.restriction_reason,
                 "provider": c.provider, "metadata": c.metadata}
                for c in self._capabilities.values()
            ],
        }


class CapabilityRegistry:
    """Tracks DeviceCapabilities across all connected devices (multi-device ready)."""

    def __init__(self):
        self._devices: dict[str, DeviceCapabilities] = {}

    def get_or_create(self, device_id: str) -> DeviceCapabilities:
        if device_id not in self._devices:
            self._devices[device_id] = DeviceCapabilities(device_id)
        return self._devices[device_id]

    def get(self, device_id: str) -> Optional[DeviceCapabilities]:
        return self._devices.get(device_id)

    def remove(self, device_id: str) -> None:
        self._devices.pop(device_id, None)

    def devices_with_capability(self, name: str) -> list[str]:
        return [dev_id for dev_id, caps in self._devices.items() if caps.has(name)]
