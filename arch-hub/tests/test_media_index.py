import json
from ecosystem_hub.storage.media_index import MediaIndex
from ecosystem_hub.storage.storage_manager import StorageManager


def test_indexes_media_and_ignores_non_media(tmp_path):
    storage = StorageManager(tmp_path)
    image = storage.write_artifact("phone", "camera_snapshot", "one.jpg", b"jpeg")
    storage.write_artifact("phone", "camera_snapshot", "notes.txt", b"no")
    items = MediaIndex(storage).refresh("phone")
    assert [item.filename for item in items] == [image.name]
    assert items[0].path.is_relative_to(tmp_path)


def test_safe_metadata_cannot_replace_indexed_path(tmp_path):
    storage = StorageManager(tmp_path)
    image = storage.write_artifact("phone", "camera_snapshot", "one.jpg", b"jpeg")
    metadata = tmp_path / "phone" / "metadata"
    metadata.mkdir(parents=True)
    (metadata / "x.json").write_text(json.dumps({"stored_path": "../../one.jpg", "media": {"lens": "rear"}}))
    item = MediaIndex(storage).refresh("phone")[0]
    assert item.path == image


def test_deletion_only_accepts_known_file_id(tmp_path):
    storage = StorageManager(tmp_path)
    storage.write_artifact("phone", "camera_snapshot", "one.jpg", b"jpeg")
    index = MediaIndex(storage)
    item = index.refresh()[0]
    assert not index.delete("../../escape")
    assert index.delete(item.file_id)
