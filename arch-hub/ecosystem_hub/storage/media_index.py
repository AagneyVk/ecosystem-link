from __future__ import annotations

import hashlib
import json
import mimetypes
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


MEDIA_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".mp4", ".webm", ".m4v"}


@dataclass(frozen=True)
class MediaItem:
    file_id: str
    device_id: str
    filename: str
    path: Path
    timestamp: float
    media_type: str
    size_bytes: int
    sha256: str
    mime_type: str
    lens: str | None = None
    capture_mode: str = "snapshot"
    burst_group_id: str | None = None
    width: int | None = None
    height: int | None = None

    def to_dict(self) -> dict[str, Any]:
        return {"file_id": self.file_id, "device_id": self.device_id,
                "filename": self.filename, "timestamp": self.timestamp,
                "timestamp_iso": datetime.fromtimestamp(self.timestamp, timezone.utc).isoformat(),
                "media_type": self.media_type, "size_bytes": self.size_bytes,
                "sha256": self.sha256, "mime_type": self.mime_type, "lens": self.lens,
                "capture_mode": self.capture_mode, "burst_group_id": self.burst_group_id,
                "width": self.width, "height": self.height}


class MediaIndex:
    """Derived media index. Only paths discovered below StorageManager.root are addressable."""
    def __init__(self, storage_manager) -> None:
        self.storage = storage_manager
        self._items: dict[str, MediaItem] = {}

    def refresh(self, device_id: str | None = None) -> list[MediaItem]:
        roots = []
        if device_id:
            roots = [self.storage.root / self.storage.safe_component(device_id, "device")]
        elif self.storage.root.exists():
            roots = [p for p in self.storage.root.iterdir() if p.is_dir() and p.name != "logs"]
        found: dict[str, MediaItem] = {}
        for root in roots:
            if not root.exists():
                continue
            for path in root.rglob("*"):
                if not path.is_file() or path.suffix.lower() not in MEDIA_EXTENSIONS:
                    continue
                resolved = path.resolve()
                if not resolved.is_relative_to(self.storage.root.resolve()):
                    continue
                stat = resolved.stat()
                rel = resolved.relative_to(self.storage.root.resolve())
                file_id = hashlib.sha256(str(rel).encode("utf-8")).hexdigest()[:24]
                meta = self._metadata_for(root.name, resolved)
                mime = mimetypes.guess_type(resolved.name)[0] or "application/octet-stream"
                found[file_id] = MediaItem(file_id, root.name, resolved.name, resolved,
                    stat.st_mtime, "image" if mime.startswith("image/") else "video",
                    stat.st_size, self.storage.sha256_of_file(resolved), mime,
                    meta.get("lens"), meta.get("capture_mode", "snapshot"),
                    meta.get("burst_group_id"), meta.get("width"), meta.get("height"))
        self._items = found
        return sorted(found.values(), key=lambda item: item.timestamp, reverse=True)

    def get(self, file_id: str) -> MediaItem | None:
        return self._items.get(file_id)

    def delete(self, file_id: str) -> bool:
        item = self._items.get(file_id)
        if not item or not item.path.resolve().is_relative_to(self.storage.root.resolve()):
            return False
        item.path.unlink(missing_ok=False)
        self._items.pop(file_id, None)
        return True

    def _metadata_for(self, device_id: str, path: Path) -> dict[str, Any]:
        metadata_dir = self.storage.root / device_id / "metadata"
        if not metadata_dir.exists():
            return {}
        for candidate in metadata_dir.glob("*.json"):
            try:
                data = json.loads(candidate.read_text(encoding="utf-8"))
                if Path(str(data.get("stored_path", ""))).name == path.name:
                    session = data.get("session", {})
                    return {**session.get("metadata", {}), **data.get("media", {})}
            except (OSError, ValueError, TypeError):
                continue
        return {}
