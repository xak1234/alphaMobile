"""Release-feed access control and atomic publication using an actual built APK."""
import hashlib
import os
from pathlib import Path

import pytest
from alphamobile.app import create_app
from alphamobile.store import Store
from alphamobile.updates import Releases
from fastapi.testclient import TestClient


@pytest.fixture
def apk() -> Path:
    path = Path(os.environ.get("ALPHAMOBILE_TEST_APK", ""))
    if not path.is_file():
        pytest.skip("Set ALPHAMOBILE_TEST_APK to an actual signed alphaMobile APK")
    return path


def test_private_feed_and_download(tmp_path: Path, apk: Path) -> None:
    store = Store(tmp_path / "alphaMobile.sqlite3")
    device = store.credential("device", "Updater test")
    agent = store.credential("agent", "Updater test agent")
    headers = {"Authorization": "Bearer " + device["token"]}
    releases = Releases(tmp_path)
    with TestClient(create_app(tmp_path)) as client:
        assert client.get("/api/updates/latest").status_code == 401
        assert client.get("/api/updates/latest", headers={"Authorization": "Bearer " + agent["token"]}).status_code == 401
        assert client.get("/api/updates/latest", headers=headers).json() == {"available": False}
        release = releases.publish(apk, "0.1.6", 7, 26, "Minimal inner screens and connected updates.")
        response = client.get("/api/updates/latest", headers=headers)
        assert response.status_code == 200 and response.json() == release
        assert response.headers["cache-control"] == "no-store"
        assert client.get(release["apk_path"]).status_code == 401
        assert client.get(release["apk_path"], headers={"Authorization": "Bearer " + agent["token"]}).status_code == 401
        download = client.get(release["apk_path"], headers=headers)
        assert download.status_code == 200
        assert len(download.content) == release["size"]
        assert hashlib.sha256(download.content).hexdigest() == release["sha256"]
        assert client.get("/api/updates/apk/not-an-apk", headers=headers).status_code == 404
        assert client.get("/api/updates/apk/" + "0" * 64, headers=headers).status_code == 404
        assert store.revoke(device["id"])
        assert client.get(release["apk_path"], headers=headers).status_code == 401


def test_release_publication_preserves_previous(tmp_path: Path, apk: Path) -> None:
    releases = Releases(tmp_path)
    first = releases.publish(apk, "0.1.6", 7, 26, "Release one")
    assert releases.publish(apk, "0.1.6", 7, 26, "Release one")["sha256"] == first["sha256"]
    with pytest.raises(ValueError, match="Version code"):
        releases.publish(apk, "0.1.5", 6, 26, "Older release")
    assert releases.latest()["version_code"] == 7
    assert releases.apk(first["sha256"]).is_file()
    assert len(list(releases.root.iterdir())) == 2  # No abandoned partial files.
    with pytest.raises(FileNotFoundError):
        releases.apk("../alphaMobile.sqlite3")
    with pytest.raises(ValueError, match="version"):
        releases.publish(apk, "bad/version", 8, 26, "")
    assert releases.latest()["version_code"] == 7
    releases.apk(first["sha256"]).write_bytes(b"interrupted transfer")
    with TestClient(create_app(tmp_path)) as client:
        device = client.app.state.store.credential("device", "Read damaged release")
        assert client.get("/api/updates/latest", headers={"Authorization": "Bearer " + device["token"]}).status_code == 503
