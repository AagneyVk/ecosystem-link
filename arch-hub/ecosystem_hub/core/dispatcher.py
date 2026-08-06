from __future__ import annotations

import logging
from typing import Any

from ecosystem_hub.core.protocol import CommandName, ErrorCode, error_payload
from ecosystem_hub.plugins.base import HubPlugin, HubPluginError

logger = logging.getLogger("ecosystem_hub.dispatcher")


class CommandDispatcher:
    """
    Routes incoming `command` messages to the plugin that owns them.

    Built-in commands (get_capabilities, get_active_sessions,
    get_runtime_state) are handled directly since they read hub-side state
    rather than triggering device-side action.
    """

    def __init__(self, *, capability_registry, session_manager, device_manager):
        self._plugins: list[HubPlugin] = []
        self._command_map: dict[str, HubPlugin] = {}
        self.capability_registry = capability_registry
        self.session_manager = session_manager
        self.device_manager = device_manager

    def register(self, plugin: HubPlugin) -> None:
        self._plugins.append(plugin)
        for cmd in plugin.handled_commands:
            self._command_map[cmd] = plugin
        logger.info("registered plugin %s for commands %s", type(plugin).__name__, plugin.handled_commands)

    async def dispatch(self, device_id: str, command: str, params: dict[str, Any]) -> dict[str, Any]:
        if command == CommandName.GET_CAPABILITIES.value:
            caps = self.capability_registry.get(device_id)
            return caps.to_dict() if caps else {"device_id": device_id, "capabilities": []}

        if command == CommandName.GET_ACTIVE_SESSIONS.value:
            return {"sessions": [s.to_dict() for s in self.session_manager.active_sessions(device_id)]}

        if command == CommandName.GET_RUNTIME_STATE.value:
            device = self.device_manager.get(device_id)
            return device.runtime_state.to_dict() if device else {}

        plugin = self._command_map.get(command)
        if not plugin:
            raise HubPluginError(ErrorCode.CAPABILITY_NOT_FOUND.value, f"No plugin handles command '{command}'")

        return await plugin.handle_command(device_id, command, params)

    async def broadcast_disconnect(self, device_id: str) -> None:
        for plugin in self._plugins:
            await plugin.on_device_disconnected(device_id)

    async def broadcast_connect(self, device_id: str) -> None:
        for plugin in self._plugins:
            await plugin.on_device_connected(device_id)
