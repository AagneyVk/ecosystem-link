from ecosystem_hub.storage.storage_manager import StorageManager


def test_peer_paths_are_confined_to_storage_root(tmp_path):
    storage = StorageManager(tmp_path)
    path = storage.write_artifact("../../escape", "camera_snapshot", "../photo.jpg", b"jpeg")
    assert path.is_relative_to(tmp_path)
    assert path.name == "photo.jpg"
    assert not list(tmp_path.rglob("*.partial"))


def test_artifact_is_committed_and_hashable(tmp_path):
    storage = StorageManager(tmp_path)
    path = storage.write_artifact("phone", "camera_snapshot", "photo.jpg", b"payload")
    assert path.read_bytes() == b"payload"
    assert storage.sha256_of_file(path) == storage.sha256_of(b"payload")
