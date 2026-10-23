"""Files on disk plus an SQLite index of installs, rides and rejections.

data_dir/
    quarantine/<install>/<ride>.zip   passed the checks, waiting to be promoted
    dataset/<install>/<ride>.zip      promoted; this is what the analysis pulls
    set_aside/<install>/<ride>.zip    quarantined rides of a blocked install
    rejected/<install>/<time>-<ride>.zip   failed a check, kept for a while to see why
    map_annotations/<install>.csv     the install's latest map annotations
    (each ride ZIP has a <ride>.json check report next to it)
"""

import hashlib
import hmac
import json
import shutil
import sqlite3
import time
from pathlib import Path

from .config import Settings

SCHEMA = """
CREATE TABLE IF NOT EXISTS installs (
    install_id TEXT PRIMARY KEY,
    secret_hash TEXT,
    trust TEXT NOT NULL DEFAULT 'new' CHECK (trust IN ('new', 'trusted', 'untrusted', 'blocked')),
    auto_promoted INTEGER NOT NULL DEFAULT 0,
    created_utc INTEGER NOT NULL,
    last_seen_utc INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS rides (
    install_id TEXT NOT NULL,
    ride_name TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('quarantine', 'dataset', 'set_aside')),
    sha256 TEXT NOT NULL,
    bytes INTEGER NOT NULL,
    app_version TEXT,
    received_utc INTEGER NOT NULL,
    promoted_utc INTEGER,
    promoted_by TEXT,
    report TEXT NOT NULL,
    PRIMARY KEY (install_id, ride_name)
);
CREATE TABLE IF NOT EXISTS rejections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    install_id TEXT NOT NULL,
    ride_name TEXT,
    app_version TEXT,
    received_utc INTEGER NOT NULL,
    status INTEGER NOT NULL,
    reason TEXT NOT NULL,
    details TEXT,
    bytes INTEGER,
    file TEXT
);
CREATE TABLE IF NOT EXISTS registrations (
    ip TEXT NOT NULL,
    utc INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS rides_received ON rides (install_id, received_utc);
CREATE INDEX IF NOT EXISTS rejections_received ON rejections (install_id, received_utc);
"""

STATE_DIRS = {"quarantine": "quarantine", "dataset": "dataset", "set_aside": "set_aside"}


def hash_secret(secret: str) -> str:
    # The secret is 256 random bits, so a plain hash is as good as a slow password hash here
    return hashlib.sha256(secret.encode()).hexdigest()


class Store:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.root = Path(settings.data_dir)
        for d in ("quarantine", "dataset", "set_aside", "rejected", "map_annotations", "tmp"):
            (self.root / d).mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.root / "index.sqlite3", check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)

    # --- installs ---

    def install(self, install_id: str):
        return self.db.execute("SELECT * FROM installs WHERE install_id = ?", (install_id,)).fetchone()

    def secret_matches(self, row, secret: str) -> bool:
        return row["secret_hash"] is not None and hmac.compare_digest(row["secret_hash"], hash_secret(secret))

    def register(self, install_id: str, secret: str, ip: str):
        now = int(time.time())
        self.db.execute(
            "INSERT INTO installs (install_id, secret_hash, created_utc, last_seen_utc) VALUES (?, ?, ?, ?)",
            (install_id, hash_secret(secret), now, now),
        )
        self.db.execute("INSERT INTO registrations (ip, utc) VALUES (?, ?)", (ip, now))

    def registrations_from(self, ip: str, since_s: int) -> int:
        return self.db.execute(
            "SELECT COUNT(*) FROM registrations WHERE ip = ? AND utc >= ?", (ip, int(time.time()) - since_s)
        ).fetchone()[0]

    def touch(self, install_id: str):
        self.db.execute("UPDATE installs SET last_seen_utc = ? WHERE install_id = ?", (int(time.time()), install_id))

    def set_trust(self, install_id: str, trust: str):
        self.db.execute("UPDATE installs SET trust = ? WHERE install_id = ?", (trust, install_id))
        if trust == "blocked":
            for ride in self.db.execute(
                "SELECT ride_name FROM rides WHERE install_id = ? AND state = 'quarantine'", (install_id,)
            ).fetchall():
                self._move(install_id, ride["ride_name"], "quarantine", "set_aside")
        else:
            for ride in self.db.execute(
                "SELECT ride_name FROM rides WHERE install_id = ? AND state = 'set_aside'", (install_id,)
            ).fetchall():
                self._move(install_id, ride["ride_name"], "set_aside", "quarantine")

    def resolve_install(self, prefix: str) -> str:
        """Full install id from the start of one, as riders quote the short form."""
        rows = self.db.execute("SELECT install_id FROM installs WHERE install_id LIKE ?", (prefix + "%",)).fetchall()
        if len(rows) != 1:
            raise KeyError(f"{len(rows)} installs match {prefix!r}")
        return rows[0]["install_id"]

    # --- rides ---

    def ride(self, install_id: str, ride_name: str):
        return self.db.execute(
            "SELECT * FROM rides WHERE install_id = ? AND ride_name = ?", (install_id, ride_name)
        ).fetchone()

    def usage_last_day(self, install_id: str) -> tuple[int, int]:
        """Rides and bytes received from an install in the last 24 h, rejected ones included."""
        since = int(time.time()) - 86_400
        a = self.db.execute(
            "SELECT COUNT(*), COALESCE(SUM(bytes), 0) FROM rides WHERE install_id = ? AND received_utc >= ?",
            (install_id, since),
        ).fetchone()
        b = self.db.execute(
            "SELECT COUNT(*), COALESCE(SUM(bytes), 0) FROM rejections WHERE install_id = ? AND received_utc >= ? AND ride_name IS NOT NULL",
            (install_id, since),
        ).fetchone()
        return a[0] + b[0], a[1] + b[1]

    def path(self, state: str, install_id: str, ride_name: str) -> Path:
        return self.root / STATE_DIRS[state] / install_id / f"{ride_name}.zip"

    def add_ride(self, install_id, ride_name, tmp: Path, sha256: str, size: int, report, promote: bool, promoted_by: str):
        state = "dataset" if promote else "quarantine"
        target = self.path(state, install_id, ride_name)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.with_suffix(".json").write_text(report.to_json())
        tmp.replace(target)
        now = int(time.time())
        self.db.execute(
            "INSERT INTO rides (install_id, ride_name, state, sha256, bytes, app_version, received_utc, promoted_utc, promoted_by, report)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (install_id, ride_name, state, sha256, size, report.app_version, now,
             now if promote else None, promoted_by if promote else None, report.to_json()),
        )
        if promote and promoted_by == "auto":
            self.db.execute("UPDATE installs SET auto_promoted = auto_promoted + 1 WHERE install_id = ?", (install_id,))
            self.db.execute(
                "UPDATE installs SET trust = 'trusted' WHERE install_id = ? AND trust = 'new' AND auto_promoted >= ?",
                (install_id, self.settings.auto_trust_after),
            )
        return state

    def _move(self, install_id, ride_name, src_state, dst_state):
        src = self.path(src_state, install_id, ride_name)
        dst = self.path(dst_state, install_id, ride_name)
        dst.parent.mkdir(parents=True, exist_ok=True)
        for s, d in ((src, dst), (src.with_suffix(".json"), dst.with_suffix(".json"))):
            if s.exists():
                s.replace(d)
        self.db.execute(
            "UPDATE rides SET state = ? WHERE install_id = ? AND ride_name = ?", (dst_state, install_id, ride_name)
        )

    def promote(self, install_id: str, ride_name: str, by: str = "admin"):
        self._move(install_id, ride_name, "quarantine", "dataset")
        self.db.execute(
            "UPDATE rides SET promoted_utc = ?, promoted_by = ? WHERE install_id = ? AND ride_name = ?",
            (int(time.time()), by, install_id, ride_name),
        )

    def quarantined(self, install_id: str | None = None):
        if install_id:
            return self.db.execute(
                "SELECT * FROM rides WHERE state = 'quarantine' AND install_id = ? ORDER BY received_utc", (install_id,)
            ).fetchall()
        return self.db.execute("SELECT * FROM rides WHERE state = 'quarantine' ORDER BY received_utc").fetchall()

    # --- rejections ---

    def reject(self, install_id, ride_name, app_version, status, reason, details, tmp: Path | None, size):
        now = int(time.time())
        kept = None
        if tmp is not None and tmp.exists():
            target = self.root / "rejected" / install_id / f"{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime(now))}-{ride_name}.zip"
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp.replace(target)
            kept = str(target.relative_to(self.root))
        self.db.execute(
            "INSERT INTO rejections (install_id, ride_name, app_version, received_utc, status, reason, details, bytes, file)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (install_id, ride_name, app_version, now, status, reason, details, size, kept),
        )

    # --- map annotations ---

    def save_map_annotations(self, install_id: str, data: bytes):
        target = self.root / "map_annotations" / f"{install_id}.csv"
        tmp = target.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.replace(target)

    # --- deletion and housekeeping ---

    def delete_install(self, install_id: str) -> int:
        """Removes every file and row of an install. A blocked install stays blocked (without its secret)."""
        removed = 0
        for d in ("quarantine", "dataset", "set_aside", "rejected"):
            folder = self.root / d / install_id
            if folder.exists():
                removed += sum(1 for p in folder.iterdir() if p.suffix == ".zip")
                shutil.rmtree(folder)
        (self.root / "map_annotations" / f"{install_id}.csv").unlink(missing_ok=True)
        self.db.execute("DELETE FROM rides WHERE install_id = ?", (install_id,))
        self.db.execute("DELETE FROM rejections WHERE install_id = ?", (install_id,))
        row = self.install(install_id)
        if row is not None and row["trust"] == "blocked":
            self.db.execute("UPDATE installs SET secret_hash = NULL WHERE install_id = ?", (install_id,))
        else:
            self.db.execute("DELETE FROM installs WHERE install_id = ?", (install_id,))
        return removed

    def cleanup(self):
        """Drops old IP addresses and old or excess rejected files."""
        s = self.settings
        now = int(time.time())
        self.db.execute("DELETE FROM registrations WHERE utc < ?", (now - s.ip_keep_days * 86_400,))
        rows = self.db.execute(
            "SELECT id, file, bytes, received_utc FROM rejections WHERE file IS NOT NULL ORDER BY received_utc DESC"
        ).fetchall()
        total = 0
        for r in rows:
            total += r["bytes"] or 0
            if r["received_utc"] < now - s.rejected_keep_days * 86_400 or total > s.rejected_max_bytes:
                (self.root / r["file"]).unlink(missing_ok=True)
                self.db.execute("UPDATE rejections SET file = NULL WHERE id = ?", (r["id"],))
        for p in (self.root / "tmp").iterdir():
            if p.stat().st_mtime < now - 3600:
                p.unlink(missing_ok=True)

    def free_bytes(self) -> int:
        return shutil.disk_usage(self.root).free


def report_dict(row) -> dict:
    return json.loads(row["report"])
