from ecosystem_hub.core.capability_registry import CapabilityRegistry


def test_update_and_query_capabilities():
    reg = CapabilityRegistry()
    caps = reg.get_or_create("device-1")
    caps.update_from_list([
        {"name": "camera.snapshot", "permission_granted": True},
        {"name": "microphone.record", "permission_granted": False},
        {"name": "accelerometer.stream", "permission_granted": True},
    ])

    assert caps.has("camera.snapshot")
    assert caps.is_permitted("camera.snapshot")
    assert not caps.is_permitted("microphone.record")
    assert not caps.has("gps.location")


def test_devices_with_capability():
    reg = CapabilityRegistry()
    reg.get_or_create("device-1").update_from_list([{"name": "camera.snapshot", "permission_granted": True}])
    reg.get_or_create("device-2").update_from_list([{"name": "microphone.record", "permission_granted": True}])

    assert reg.devices_with_capability("camera.snapshot") == ["device-1"]
    assert reg.devices_with_capability("microphone.record") == ["device-2"]


def test_by_category():
    reg = CapabilityRegistry()
    caps = reg.get_or_create("device-1")
    caps.update_from_list([
        {"name": "camera.snapshot", "permission_granted": True},
        {"name": "camera.stream", "permission_granted": True},
        {"name": "microphone.record", "permission_granted": True},
    ])
    assert len(caps.by_category("camera")) == 2
    assert len(caps.by_category("microphone")) == 1
