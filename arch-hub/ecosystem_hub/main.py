from __future__ import annotations

import argparse
import asyncio
import logging
import signal
import errno

from ecosystem_hub.config.config import HubConfig
from ecosystem_hub.core.capability_registry import CapabilityRegistry
from ecosystem_hub.core.device_manager import DeviceManager
from ecosystem_hub.core.dispatcher import CommandDispatcher
from ecosystem_hub.core.session_manager import SessionManager
from ecosystem_hub.core.job_manager import JobManager
from ecosystem_hub.storage.storage_manager import StorageManager
from ecosystem_hub.storage.media_index import MediaIndex
from ecosystem_hub.transfer.file_transfer_server import FileTransferServer
from ecosystem_hub.server.websocket_server import WebSocketHub
from ecosystem_hub.server.admin_server import AdminServer
from ecosystem_hub.streaming.streaming_server import StreamingServer
from ecosystem_hub.ui.web_server import UIServer
from ecosystem_hub.plugins.base import PluginContext
from ecosystem_hub.plugins.camera_plugin import CameraPlugin
from ecosystem_hub.plugins.audio_plugin import AudioPlugin
from ecosystem_hub.plugins.device_features_plugin import DeviceFeaturesPlugin

logger = logging.getLogger("ecosystem_hub")


async def async_main(config: HubConfig) -> None:
    logging.basicConfig(level=getattr(logging, config.log_level.upper(), logging.INFO),
                         format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    capability_registry = CapabilityRegistry()
    device_manager = DeviceManager()
    session_manager = SessionManager(retention_seconds=config.session_retention_seconds)
    job_manager = JobManager()
    storage_manager = StorageManager(config.storage_root)
    media_index = MediaIndex(storage_manager)

    dispatcher = CommandDispatcher(
        capability_registry=capability_registry,
        session_manager=session_manager,
        device_manager=device_manager,
    )

    transfer_server = FileTransferServer(
        storage_manager=storage_manager,
        session_manager=session_manager,
        host=config.transfer_host,
        port=config.transfer_port,
        job_manager=job_manager,
    )

    streaming_server = StreamingServer(
        host=config.streaming_host,
        port=config.streaming_port,
    )
    streaming_server.mount_on(transfer_server.app)

    # UI server is created first so we can pass broadcast_ui into PluginContext.
    ui_server = UIServer(
        dispatcher=dispatcher,
        device_manager=device_manager,
        session_manager=session_manager,
        capability_registry=capability_registry,
        storage_manager=storage_manager,
        job_manager=job_manager,
        media_index=media_index,
        streaming_server=streaming_server,
        transfer_server=transfer_server,
        host=config.ui_host,
        port=config.ui_port,
    )

    ws_hub = WebSocketHub(
        dispatcher=dispatcher,
        capability_registry=capability_registry,
        device_manager=device_manager,
        session_manager=session_manager,
        host=config.ws_host,
        port=config.ws_port,
        shared_secret=config.shared_secret or None,
    )

    # Hook WebSocketHub so it notifies the UI when devices connect/disconnect.
    ws_hub.on_device_event = ui_server.broadcast
    transfer_server.on_complete = ui_server.broadcast

    admin_server = AdminServer(
        dispatcher=dispatcher,
        device_manager=device_manager,
        host=config.admin_host,
        port=config.admin_port,
    )

    plugin_ctx = PluginContext(
        session_manager=session_manager,
        capability_registry=capability_registry,
        device_manager=device_manager,
        storage_manager=storage_manager,
        message_sender=ws_hub.send_to_device,
        transfer_server=transfer_server,
        broadcast_ui=ui_server.broadcast,
        job_manager=job_manager,
    )

    dispatcher.register(CameraPlugin(plugin_ctx))
    dispatcher.register(AudioPlugin(plugin_ctx))
    dispatcher.register(DeviceFeaturesPlugin(plugin_ctx))
    # Future plugins register here: ClipboardPlugin, NotificationPlugin, etc.

    async def start_with_vpn_retry(name, starter, attempts=30):
        for attempt in range(1, attempts + 1):
            try:
                await starter()
                return
            except OSError as exc:
                transient = exc.errno in {errno.EADDRNOTAVAIL, errno.ENETDOWN, errno.ENETUNREACH}
                if not transient or attempt == attempts:
                    raise
                logger.warning("%s bind unavailable (%s); waiting for VPN address, attempt %d/%d",
                               name, exc, attempt, attempts)
                await asyncio.sleep(2)

    await start_with_vpn_retry("transfer", transfer_server.start)
    await start_with_vpn_retry("websocket", ws_hub.start)
    await admin_server.start()
    await ui_server.start()

    logger.info(
        "ecosystem hub fully started.\n"
        "  control: ws://%s:%d\n"
        "  transfer: http://%s:%d\n"
        "  streaming: ws://%s:%d/stream\n"
        "  ui: http://%s:%d\n"
        "  admin: http://%s:%d\n"
        "  storage: %s",
        config.ws_host, config.ws_port,
        config.transfer_host, config.transfer_port,
        config.transfer_host, config.transfer_port,
        config.ui_host, config.ui_port,
        config.admin_host, config.admin_port,
        config.storage_root,
    )

    stop_event = asyncio.Event()

    def _handle_signal():
        logger.info("shutdown signal received")
        stop_event.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, _handle_signal)

    async def prune_loop():
        while not stop_event.is_set():
            await asyncio.sleep(300)
            session_manager.prune_old()

    prune_task = asyncio.create_task(prune_loop())

    await stop_event.wait()

    prune_task.cancel()
    await ui_server.stop()
    await ws_hub.stop()
    await transfer_server.stop()
    await admin_server.stop()
    logger.info("ecosystem hub stopped cleanly")


def main() -> None:
    parser = argparse.ArgumentParser(description="Ecosystem Hub — Linux side of the personal device ecosystem")
    parser.add_argument("--config", help="Path to config.toml", default=None)
    args = parser.parse_args()

    config = HubConfig.load(args.config)
    asyncio.run(async_main(config))


if __name__ == "__main__":
    main()
