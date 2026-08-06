"""
Storage manager.

All transferred artifacts (and future ones - video, clipboard snippets,
handed-off files) land in a unified, configurable hierarchy:

    <root>/<device_id>/<category>/<YYYY>/<MM>/<DD>/<filename>
    <root>/<device_id>/metadata/<session_id>.json
    <root>/logs/<date>.log

Keeping device_id in the path from day one avoids a painful migration when
a second Android device (or another device class entirely) joins the
ecosystem.

Plugins register their own session_type → category mapping via
register_category() rather than a hardcoded dict, so adding a new plugin
requires zero changes here.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import time
from pathlib import Path
from typing import Any

logger = logging.getLogger("ecosystem_hub.storage")

# Built-in fallback mappings — plugins can override/extend via register_category().
_DEFAULT_CATEGORY_MAP: dict[str, str] = {
    "camera_snapshot": "camera",
    "camera_stream": "camera",
    "microphone_record": "audio",
    "microphone_stream": "audio",
    "screen_recording": "screen",
    "manual_file": "files",
    "outbound_file": "outbound",
}


class StorageManager:
    def __init__(self, root: str | Path):
        self.root = Path(root).expanduser()
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / "logs").mkdir(exist_ok=True)
        self._category_map: dict[str, str] = dict(_DEFAULT_CATEGORY_MAP)

    def register_category(self, session_type: str, category: str) -> None:
        """Allow plugins to declare their session_type → storage category mapping."""
        self._category_map[session_type] = category
        logger.debug("registered storage category: %s -> %s", session_type, category)

    def category_for(self, session_type: str) -> str:
        return self._category_map.get(session_type, "misc")

    def path_for(self, device_id: str, session_type: str, filename: str) -> Path:
        category = self.category_for(session_type)
        date = time.strftime("%Y/%m/%d")
        directory = self.root / self.safe_component(device_id, "device") / self.safe_component(category, "misc") / date
        directory.mkdir(parents=True, exist_ok=True)
        return directory / self.safe_component(filename, "artifact.bin")

    def write_artifact(self, device_id: str, session_type: str, filename: str, data: bytes) -> Path:
        dest = self.path_for(device_id, session_type, filename)
        partial = dest.with_name(dest.name + ".partial")
        with open(partial, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(partial, dest)
        logger.info("stored artifact %s (%d bytes)", dest, len(data))
        return dest

    def write_metadata(self, device_id: str, session_id: str, metadata: dict[str, Any]) -> Path:
        directory = self.root / self.safe_component(device_id, "device") / "metadata"
        directory.mkdir(parents=True, exist_ok=True)
        dest = directory / f"{self.safe_component(session_id, 'session')}.json"
        partial = dest.with_name(dest.name + ".partial")
        partial.write_text(json.dumps(metadata, indent=2, default=str), encoding="utf-8")
        os.replace(partial, dest)
        return dest

    def list_artifacts(self, device_id: str, category: str | None = None) -> list[Path]:
        """List all stored artifact files for a device, optionally filtered by category."""
        base = self.root / device_id
        if category:
            base = base / category
        if not base.exists():
            return []
        return sorted(base.rglob("*.*"), key=lambda p: p.stat().st_mtime, reverse=True)

    @staticmethod
    def sha256_of(data: bytes) -> str:
        return hashlib.sha256(data).hexdigest()

    @staticmethod
    def sha256_of_file(path: Path) -> str:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                h.update(chunk)
        return h.hexdigest()

    @staticmethod
    def safe_component(value: str, fallback: str) -> str:
        """Return one portable path component; peer input may never select a path."""
        value = Path(str(value)).name.strip()
        cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", value).strip(". ")
        return cleaned[:180] or fallback
