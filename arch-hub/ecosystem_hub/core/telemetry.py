from __future__ import annotations

import math
import time
from typing import Any


def validate_sensor_sample(payload: dict[str, Any], *, max_axes: int = 16) -> dict[str, Any]:
    capability = str(payload.get("capability", ""))
    values = payload.get("values")
    if not capability.startswith("sensor.") or not isinstance(values, list) or not 1 <= len(values) <= max_axes:
        raise ValueError("invalid sensor sample shape")
    numeric = [float(value) for value in values]
    if not all(math.isfinite(value) for value in numeric):
        raise ValueError("sensor values must be finite")
    return {**payload, "capability": capability, "values": numeric}


def location_fix_is_stale(fix_timestamp_ms: int, *, now_ms: int | None = None,
                          maximum_age_ms: int = 30_000) -> bool:
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    return max(0, now_ms - int(fix_timestamp_ms)) > maximum_age_ms
