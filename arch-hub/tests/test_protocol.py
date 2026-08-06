import pytest

from ecosystem_hub.core.protocol import Envelope


def test_envelope_round_trip_preserves_correlation_id():
    original = Envelope(type="command", payload={"command": "take_photo"}, correlation_id="request-1")
    decoded = Envelope.from_dict(original.to_dict())
    decoded.validate()
    assert decoded.correlation_id == "request-1"
    assert decoded.msg_id == original.msg_id


def test_legacy_in_reply_to_becomes_correlation_id():
    decoded = Envelope.from_dict({
        "proto_version": 1, "msg_id": "reply-1", "type": "response",
        "payload": {}, "in_reply_to": "request-1",
    })
    assert decoded.correlation_id == "request-1"


def test_unsupported_version_is_rejected():
    envelope = Envelope.from_dict({"proto_version": 99, "msg_id": "x", "type": "hello", "payload": {}})
    with pytest.raises(ValueError, match="unsupported protocol"):
        envelope.validate()
