from ecosystem_hub.core.capability_registry import DeviceCapabilities


def test_rich_capability_state_parsing():
    caps = DeviceCapabilities("phone")
    caps.update_from_list([{"capability_id": "sensor.accelerometer", "name": "sensor.accelerometer",
        "available": True, "operations": ["start", "stop"], "permission_state": "granted",
        "provider": "android.sensor", "metadata": {"vendor": "test"}}])
    cap = caps.all()[0]
    assert cap.operations == ["start", "stop"]
    assert cap.provider == "android.sensor"
    assert caps.has("sensor.accelerometer")


def test_unavailable_capability_is_not_reported_as_supported():
    caps = DeviceCapabilities("phone")
    caps.update_from_list([{"name": "screen.record", "available": False,
                            "restriction_reason": "consent required"}])
    assert not caps.has("screen.record")
