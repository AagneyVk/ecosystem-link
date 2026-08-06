"""
Plugin architecture.

Each capability category (camera, microphone, and future ones like
clipboard or notifications) is implemented as a Plugin. Plugins are
registered with the CommandDispatcher and own:

    - which commands they respond to
    - how to validate the device supports/permits the operation
    - how to turn a command into an outgoing message to the device
    - how to handle the response / any associated file transfer

This keeps command handling out of the WebSocket transport layer entirely,
so new capabilities are added by writing a new plugin class, not by
touching the server or protocol code.
"""

from __future__ import annotations

import abc
from typing import Any, Callable, Awaitable


class HubPlugin(abc.ABC):
    """Base class for all hub-side capability plugins."""

    #: Command names (from protocol.CommandName or future extensions) this
    #: plugin claims responsibility for.
    handled_commands: set[str] = set()

    def __init__(self, context: "PluginContext"):
        self.ctx = context

    @abc.abstractmethod
    async def handle_command(self, device_id: str, command: str, params: dict[str, Any]) -> dict[str, Any]:
        """
        Execute a command for a device. Returns the payload to send back as
        a `response` message. Raise HubPluginError for structured failures.
        """
        raise NotImplementedError

    async def on_device_connected(self, device_id: str) -> None:
        """Optional hook, e.g. to resume/verify sessions on reconnect."""
        return None

    async def on_device_disconnected(self, device_id: str) -> None:
        """Optional hook, e.g. to mark in-flight sessions failed."""
        return None


class HubPluginError(Exception):
    def __init__(self, code: str, message: str, **extra):
        super().__init__(message)
        self.code = code
        self.message = message
        self.extra = extra


# Type alias for the UI push callback injected into context.
UIBroadcastFn = Callable[[dict], Awaitable[None]]


class PluginContext:
    """
    Dependency-injection container passed to every plugin so plugins never
    reach into global state. Makes plugins independently unit-testable with
    mock managers.
    """

    def __init__(self, *, session_manager, capability_registry, device_manager,
                 storage_manager, message_sender, transfer_server,
                 broadcast_ui: UIBroadcastFn | None = None, job_manager=None):
        self.session_manager = session_manager
        self.capability_registry = capability_registry
        self.device_manager = device_manager
        self.storage_manager = storage_manager
        self.send_to_device = message_sender  # async fn(device_id, Envelope) -> None
        self.transfer_server = transfer_server
        self.job_manager = job_manager
        self._broadcast_ui = broadcast_ui  # async fn(dict) -> None, optional

    def register_storage_category(self, session_type: str, category: str) -> None:
        """Convenience helper so plugins can declare their storage category at init."""
        self.storage_manager.register_category(session_type, category)

    async def broadcast_to_ui(self, message: dict) -> None:
        """Push a message to all connected browser UI clients (no-op if UI server not running)."""
        if self._broadcast_ui:
            await self._broadcast_ui(message)
