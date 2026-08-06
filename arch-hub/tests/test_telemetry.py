import pytest
from ecosystem_hub.core.telemetry import location_fix_is_stale, validate_sensor_sample


def test_sensor_sample_validation():
    sample = validate_sensor_sample({"capability": "sensor.accelerometer", "values": [1, 2, 3]})
    assert sample["values"] == [1.0, 2.0, 3.0]
    with pytest.raises(ValueError):
        validate_sensor_sample({"capability": "sensor.accelerometer", "values": [float("nan")]})


def test_stale_location_detection():
    assert not location_fix_is_stale(90_000, now_ms=100_000)
    assert location_fix_is_stale(60_000, now_ms=100_000)
