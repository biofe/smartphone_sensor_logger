import hashlib
import secrets
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from velowind_upload import admin
from velowind_upload.config import Settings
from velowind_upload.main import create_app

from .rides import ride_zip


@pytest.fixture
def settings(tmp_path):
    return Settings(data_dir=tmp_path, min_free_bytes=0)


@pytest.fixture
def client(settings):
    return TestClient(create_app(settings))


class Phone:
    """One app install."""

    def __init__(self, client):
        self.client = client
        self.id = str(uuid.uuid4())
        self.secret = secrets.token_urlsafe(32)

    def headers(self, data=b""):
        return {
            "X-Install-Id": self.id,
            "Authorization": f"Bearer {self.secret}",
            "X-App-Version": "0.8.0",
            "X-Content-SHA256": hashlib.sha256(data).hexdigest(),
        }

    def upload(self, name="20261020-1213Z-k3f9", data=None, **kw):
        data = data if data is not None else ride_zip(name=name, **kw)
        return self.client.post(f"/v1/rides/{name}", content=data, headers=self.headers(data))


def state(client, phone, name="20261020-1213Z-k3f9"):
    row = client.app.state.store.ride(phone.id, name)
    return row["state"] if row else None


def trust(client, phone):
    return client.app.state.store.install(phone.id)["trust"]


def test_health(client):
    assert client.get("/v1/health").json() == {"status": "ok", "min_app_version": "0.8.0"}


def test_ride_in_area_goes_to_dataset(client, settings):
    phone = Phone(client)
    r = phone.upload()
    assert r.status_code == 201
    assert state(client, phone) == "dataset"
    assert (settings.data_dir / "dataset" / phone.id / "20261020-1213Z-k3f9.zip").exists()
    assert (settings.data_dir / "dataset" / phone.id / "20261020-1213Z-k3f9.json").exists()


def test_same_ride_twice_is_a_duplicate(client):
    phone = Phone(client)
    assert phone.upload().status_code == 201
    r = phone.upload()
    assert (r.status_code, r.json()["status"]) == (200, "duplicate")


def test_ride_outside_area_waits_in_quarantine(client):
    phone = Phone(client)
    assert phone.upload(start=(48.21, 16.37)).status_code == 201
    assert state(client, phone) == "quarantine"
    admin_store = client.app.state.store
    admin_store.promote(phone.id, "20261020-1213Z-k3f9")
    assert state(client, phone) == "dataset"


def test_install_becomes_trusted_after_five_auto_promotions(client):
    phone = Phone(client)
    for i in range(5):
        assert phone.upload(name=f"20261020-121{i}Z-k3f9").status_code == 201
        assert trust(client, phone) == ("trusted" if i == 4 else "new")
    assert trust(client, phone) == "trusted"
    assert phone.upload(name="20261020-1220Z-k3f9", start=(48.21, 16.37)).status_code == 201
    assert state(client, phone, "20261020-1220Z-k3f9") == "dataset"


def test_untrusted_install_always_waits(client):
    phone = Phone(client)
    phone.upload(name="20261020-1210Z-k3f9")
    client.app.state.store.set_trust(phone.id, "untrusted")
    phone.upload(name="20261020-1211Z-k3f9")
    assert state(client, phone, "20261020-1211Z-k3f9") == "quarantine"


def test_blocked_install_is_refused_and_its_quarantine_set_aside(client):
    phone = Phone(client)
    phone.upload(start=(48.21, 16.37))
    client.app.state.store.set_trust(phone.id, "blocked")
    assert state(client, phone) == "set_aside"
    assert phone.upload(name="20261020-1211Z-k3f9").status_code == 403


def test_wrong_secret_is_refused(client):
    phone = Phone(client)
    phone.upload()
    phone.secret = secrets.token_urlsafe(32)
    assert phone.upload(name="20261020-1211Z-k3f9").status_code == 401


@pytest.mark.parametrize("install_id, secret", [("not-a-uuid", secrets.token_urlsafe(32)), (str(uuid.uuid4()), "short")])
def test_bad_identity_is_refused(client, install_id, secret):
    data = ride_zip()
    r = client.post(
        "/v1/rides/20261020-1213Z-k3f9",
        content=data,
        headers={"X-Install-Id": install_id, "Authorization": f"Bearer {secret}", "X-Content-SHA256": hashlib.sha256(data).hexdigest()},
    )
    assert r.status_code == 401


def test_damaged_transfer_asks_for_retry(client):
    phone = Phone(client)
    data = ride_zip()
    r = client.post("/v1/rides/20261020-1213Z-k3f9", content=data[:-100], headers=phone.headers(data))
    assert (r.status_code, r.json()["status"]) == (422, "retry")
    assert state(client, phone) is None


def test_rejection_is_logged_with_file(client, settings):
    phone = Phone(client)
    r = phone.upload(speed_mps=30)
    assert r.status_code == 400
    assert r.json() == {"status": "rejected", "reason": "Ride is too fast for a bike"}
    row = client.app.state.store.db.execute("SELECT * FROM rejections").fetchone()
    assert row["install_id"] == phone.id and row["app_version"] == "0.8.0" and "median 30.0" in row["details"]
    assert (settings.data_dir / row["file"]).exists()


def test_too_large_is_413(tmp_path):
    client = TestClient(create_app(Settings(data_dir=tmp_path, min_free_bytes=0, max_ride_bytes=1000)))
    assert Phone(client).upload().status_code == 413


def test_full_disk_is_503(tmp_path):
    client = TestClient(create_app(Settings(data_dir=tmp_path, min_free_bytes=10**18)))
    r = Phone(client).upload()
    assert r.status_code == 503 and "Retry-After" in r.headers


def test_rate_limit_per_ip(tmp_path):
    client = TestClient(create_app(Settings(data_dir=tmp_path, min_free_bytes=0, requests_per_ip_per_hour=2)))
    phone = Phone(client)
    assert phone.upload(name="20261020-1210Z-k3f9").status_code == 201
    assert phone.upload(name="20261020-1211Z-k3f9").status_code == 201
    r = phone.upload(name="20261020-1212Z-k3f9")
    assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0


def test_daily_ride_limit_per_install(tmp_path):
    client = TestClient(create_app(Settings(data_dir=tmp_path, min_free_bytes=0, rides_per_install_per_day=1)))
    phone = Phone(client)
    assert phone.upload(name="20261020-1210Z-k3f9").status_code == 201
    assert phone.upload(name="20261020-1211Z-k3f9").status_code == 429


def test_new_installs_per_ip(tmp_path):
    client = TestClient(create_app(Settings(data_dir=tmp_path, min_free_bytes=0, new_installs_per_ip_per_day=1)))
    assert Phone(client).upload().status_code == 201
    assert Phone(client).upload().status_code == 429


def test_map_annotations_replace_previous(client, settings):
    phone = Phone(client)
    csv = b"t_utc_ms,lat,lon,accuracy_m,source,overall\n1792589602000,51.5,9.9,,map,good\n"
    for _ in range(2):
        r = client.put("/v1/map-annotations", content=csv, headers=phone.headers())
        assert r.status_code == 200
    assert (settings.data_dir / "map_annotations" / f"{phone.id}.csv").read_bytes() == csv


def test_delete_removes_everything(client, settings):
    phone = Phone(client)
    phone.upload(name="20261020-1210Z-k3f9")
    phone.upload(name="20261020-1211Z-k3f9", start=(48.21, 16.37))
    phone.upload(name="20261020-1212Z-k3f9", speed_mps=30)
    r = client.delete("/v1/install", headers=phone.headers())
    assert r.json() == {"status": "deleted", "rides": 3}
    store = client.app.state.store
    assert store.install(phone.id) is None
    assert store.db.execute("SELECT COUNT(*) FROM rejections").fetchone()[0] == 0
    assert not any(settings.data_dir.glob(f"*/{phone.id}"))


def test_delete_of_unknown_install_is_fine(client):
    assert client.delete("/v1/install", headers=Phone(client).headers()).json()["rides"] == 0


def test_admin_commands(client, settings, monkeypatch, capsys):
    monkeypatch.setenv("VELOWIND_DATA_DIR", str(settings.data_dir))
    phone = Phone(client)
    phone.upload(start=(48.21, 16.37))
    phone.upload(name="20261020-1211Z-k3f9", speed_mps=30)
    admin.main(["report"])
    out = capsys.readouterr().out
    assert "Quarantine: 1 rides" in out and "Ride is too fast for a bike" in out
    admin.main(["promote", phone.id[:8]])
    assert state(client, phone) == "dataset"
    admin.main(["block", phone.id[:8]])
    assert trust(client, phone) == "blocked"
    admin.main(["delete", phone.id[:8]])
    assert client.app.state.store.install(phone.id)["secret_hash"] is None  # stays blocked


def test_cleanup_drops_old_rejected_files_and_ips(client, settings):
    phone = Phone(client)
    phone.upload(speed_mps=30)
    store = client.app.state.store
    old = int(time.time()) - 40 * 86_400
    store.db.execute("UPDATE rejections SET received_utc = ?", (old,))
    store.db.execute("UPDATE registrations SET utc = ?", (old,))
    store.cleanup()
    assert store.db.execute("SELECT file FROM rejections").fetchone()["file"] is None
    assert store.db.execute("SELECT COUNT(*) FROM registrations").fetchone()[0] == 0
    assert not list((settings.data_dir / "rejected").rglob("*.zip"))
