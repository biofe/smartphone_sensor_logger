"""Checks every uploaded ride ZIP before it is stored: safe to unpack, the expected format, plausible values."""

import csv
import io
import json
import math
import re
import statistics
import time
import zipfile
from dataclasses import dataclass, field

from . import geo
from .config import Settings

RIDE_NAME = re.compile(r"^(\d{8}-\d{4}Z-[a-z0-9]{4}|[0-9a-f]{8})$")

HEADERS = {
    "accel.csv": ["t_utc_ms", "ax", "ay", "az"],
    "gyro.csv": ["t_utc_ms", "gx", "gy", "gz"],
    "pressure.csv": ["t_utc_ms", "pressure_hpa"],
    "gps.csv": ["t_utc_ms", "gps_time_ms", "lat", "lon", "alt_m", "speed_mps", "bearing_deg", "accuracy_m"],
}
SOUND_BANDS = 26
REQUIRED = {"meta.json", "gps.csv", "accel.csv"}
ALLOWED = REQUIRED | set(HEADERS) | {"sound.csv", "annotations.csv"}

# Present in meta.json on the phone, removed by the export: their presence means the ride was not trimmed
UNTRIMMED_KEYS = {"started_utc_ms", "stopped_utc_ms", "still_since_utc_ms", "trim_start_m", "trim_end_m", "name_suffix"}

EARLIEST_UTC_MS = 1_704_067_200_000  # 2024-01-01
MIN_DURATION_S = 120
MAX_DURATION_S = 8 * 3600
MAX_ANNOTATIONS = 1000

# Rows per second, (low, high); a GPS without fix in a tunnel or under trees gives fewer rows
RATES = {
    "accel.csv": (50, 500),
    "gyro.csv": (50, 500),
    "pressure.csv": (2, 50),
    "gps.csv": (0.3, 2),
    "sound.csv": (5, 12),
}
MAX_MEDIAN_SPEED_MPS = 15
GRAVITY_RANGE = (5.0, 15.0)
DBFS_RANGE = (-200.0, 20.0)


class Rejected(Exception):
    """The upload breaks a rule; retrying the same file won't help. `reason` is shown to the rider."""

    def __init__(self, reason: str, details: str = ""):
        super().__init__(reason)
        self.reason = reason
        self.details = details


@dataclass
class Report:
    ride_name: str
    app_version: str = ""
    bike_type: str = ""
    device: str = ""
    duration_s: float = 0.0
    distance_km: float = 0.0
    rows: dict = field(default_factory=dict)
    rates_hz: dict = field(default_factory=dict)
    bbox: list = field(default_factory=list)  # [min lat, min lon, max lat, max lon]
    area_fraction: float = 0.0
    annotations: int = 0

    def to_json(self) -> str:
        return json.dumps(self.__dict__, indent=2)


def check_ride_name(name: str):
    if not RIDE_NAME.match(name):
        raise Rejected("Invalid ride name", f"got {name[:40]!r}")


def check_ride_zip(path, ride_name: str, settings: Settings, now_ms: int | None = None) -> Report:
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    check_ride_name(ride_name)
    try:
        zf = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as e:
        raise Rejected("Not a ZIP file", str(e))
    with zf:
        files = _check_entries(zf, ride_name, settings)
        meta = _check_meta(zf, files["meta.json"], ride_name, now_ms)
        report = Report(
            ride_name=ride_name,
            app_version=str(meta.get("app_version", "")),
            bike_type=str(meta.get("bike_type", "")),
            device=str(meta.get("device", "")),
        )
        t0, t1 = meta["kept_from_utc_ms"], meta["kept_to_utc_ms"]
        report.duration_s = round((t1 - t0) / 1000, 1)

        for name in ("gps.csv", "accel.csv", "gyro.csv", "pressure.csv", "sound.csv", "annotations.csv"):
            if name in files:
                _check_csv(zf, files[name], name, t0, t1, report)
    return report


def _check_entries(zf: zipfile.ZipFile, ride_name: str, settings: Settings) -> dict:
    infos = zf.infolist()
    if len(infos) > len(ALLOWED):
        raise Rejected("Unexpected files in the ZIP", f"{len(infos)} entries")
    files = {}
    unpacked = 0
    for info in infos:
        folder, _, name = info.filename.partition("/")
        if folder != ride_name or name not in ALLOWED or info.is_dir():
            raise Rejected("Unexpected files in the ZIP", f"entry {info.filename[:80]!r}")
        if name in files:
            raise Rejected("Unexpected files in the ZIP", f"duplicate {name}")
        if info.flag_bits & 0x1:
            raise Rejected("Encrypted ZIP entries are not accepted", name)
        if info.file_size > max(settings.max_unpack_ratio * info.compress_size, 1024 * 1024):
            raise Rejected("ZIP entry unpacks too large", f"{name}: {info.compress_size} -> {info.file_size} bytes")
        unpacked += info.file_size
        files[name] = info
    if unpacked > settings.max_unpacked_bytes:
        raise Rejected("Ride is too large when unpacked", f"{unpacked} bytes")
    missing = REQUIRED - files.keys()
    if missing:
        raise Rejected("Ride is missing files", ", ".join(sorted(missing)))
    return files


def _check_meta(zf, info, ride_name: str, now_ms: int) -> dict:
    if info.file_size > 64 * 1024:
        raise Rejected("meta.json is too large", f"{info.file_size} bytes")
    try:
        meta = json.loads(zf.read(info))
    except (ValueError, UnicodeDecodeError) as e:
        raise Rejected("meta.json is not valid JSON", str(e)[:200])
    if not isinstance(meta, dict):
        raise Rejected("meta.json is not valid JSON", "not an object")
    leftover = UNTRIMMED_KEYS & meta.keys()
    if leftover:
        raise Rejected("Ride was not privacy-trimmed", ", ".join(sorted(leftover)))
    if meta.get("ride_id") != ride_name:
        raise Rejected("Ride name doesn't match meta.json", f"ride_id {str(meta.get('ride_id'))[:40]!r}")
    t0, t1 = meta.get("kept_from_utc_ms"), meta.get("kept_to_utc_ms")
    if not all(isinstance(t, int) and not isinstance(t, bool) for t in (t0, t1)):
        raise Rejected("meta.json lacks the kept time window", "kept_from_utc_ms / kept_to_utc_ms")
    if not EARLIEST_UTC_MS <= t0 < t1 <= now_ms + 86_400_000:
        raise Rejected("Ride time is implausible", f"{t0} to {t1}")
    duration = (t1 - t0) / 1000
    if not MIN_DURATION_S <= duration <= MAX_DURATION_S:
        raise Rejected("Ride duration is implausible", f"{duration:.0f} s, expected {MIN_DURATION_S} to {MAX_DURATION_S}")
    return meta


def _rows(zf, info):
    with zf.open(info) as raw:
        text = io.TextIOWrapper(raw, encoding="utf-8", newline="")
        yield from csv.reader(text)


def _number(value: str, name: str, column: str) -> float:
    try:
        x = float(value)
    except ValueError:
        raise Rejected(f"{name} has a value that isn't a number", f"{column}={value[:20]!r}")
    if not math.isfinite(x):
        raise Rejected(f"{name} has a value that isn't a number", f"{column}={value[:20]!r}")
    return x


def _check_header(name: str, header: list[str]):
    if name in HEADERS:
        ok = header == HEADERS[name]
    elif name == "sound.csv":
        ok = (
            header[:2] == ["t_utc_ms", "laeq_dbfs"]
            and len(header) == 2 + SOUND_BANDS
            and all(re.fullmatch(r"band_\d+_dbfs", h) for h in header[2:])
        )
    else:  # annotations: columns changed between app versions, the first three never did
        ok = header[:3] == ["t_utc_ms", "lat", "lon"] and len(header) <= 30
    if not ok:
        raise Rejected(f"{name} has unexpected columns", ",".join(header)[:200])


def _check_csv(zf, info, name: str, t0: int, t1: int, report: Report):
    rows = _rows(zf, info)
    try:
        header = next(rows)
    except StopIteration:
        raise Rejected(f"{name} is empty")
    except (UnicodeDecodeError, csv.Error) as e:
        raise Rejected(f"{name} can't be read", str(e)[:200])
    _check_header(name, header)
    width = len(header)

    count = 0
    first = last = None
    gravity = []
    speeds = []
    points = []
    distance_m = 0.0
    try:
        for row in rows:
            if not row:
                continue
            count += 1
            if name == "annotations.csv":
                if count > MAX_ANNOTATIONS:
                    raise Rejected("Too many annotations", f"more than {MAX_ANNOTATIONS}")
                t = _number(row[0], name, "t_utc_ms")
                if len(row) < 3 or len(row) > width:
                    raise Rejected(f"{name} has rows of the wrong length", f"row {count}")
                lat, lon = _number(row[1], name, "lat"), _number(row[2], name, "lon")
                if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                    raise Rejected("Annotation position is invalid", f"{lat}, {lon}")
                continue
            if len(row) != width:
                raise Rejected(f"{name} has rows of the wrong length", f"row {count}: {len(row)} columns, expected {width}")
            t = _number(row[0], name, "t_utc_ms")
            if not t0 - 1000 <= t <= t1 + 1000:
                raise Rejected(f"{name} has rows outside the ride's time window", f"row {count}: t={t:.0f}")
            if last is not None and t < last:
                raise Rejected(f"{name} timestamps go backwards", f"row {count}")
            if first is None:
                first = t
            last = t
            if name == "accel.csv" and count % 10 == 0:
                ax, ay, az = (_number(v, name, "a") for v in row[1:4])
                gravity.append(math.sqrt(ax * ax + ay * ay + az * az))
            elif name == "gyro.csv" and count % 10 == 0:
                for v in row[1:4]:
                    _number(v, name, "g")
            elif name == "sound.csv" and count % 8 == 0:
                for v in row[1:]:
                    if not DBFS_RANGE[0] <= _number(v, name, "level") <= DBFS_RANGE[1]:
                        raise Rejected("Sound levels are implausible", f"row {count}: {v}")
            elif name == "pressure.csv":
                p = _number(row[1], name, "pressure_hpa")
                if not 500 <= p <= 1100:
                    raise Rejected("Air pressure is implausible", f"{p} hPa")
            elif name == "gps.csv":
                lat, lon = _number(row[2], name, "lat"), _number(row[3], name, "lon")
                if not (-90 <= lat <= 90 and -180 <= lon <= 180) or (lat == 0 and lon == 0):
                    raise Rejected("GPS position is invalid", f"row {count}: {lat}, {lon}")
                if points:
                    distance_m += _distance_m(points[-1], (lat, lon))
                points.append((lat, lon))
                if row[5]:
                    speeds.append(_number(row[5], name, "speed_mps"))
    except (UnicodeDecodeError, csv.Error) as e:
        raise Rejected(f"{name} can't be read", str(e)[:200])

    report.rows[name] = count
    if name == "annotations.csv":
        report.annotations = count
        return
    if count < 2:
        raise Rejected(f"{name} has no data inside the ride's time window", f"{count} rows")
    span_s = (last - first) / 1000
    low, high = RATES[name]
    rate = (count - 1) / span_s if span_s > 0 else 0
    report.rates_hz[name] = round(rate, 2)
    if span_s < MIN_DURATION_S / 2 or not low <= rate <= high:
        raise Rejected(f"{name} sample rate is implausible", f"{rate:.2f} rows/s over {span_s:.0f} s, expected {low} to {high}")

    if name == "accel.csv" and gravity:
        g = statistics.fmean(gravity)
        if not GRAVITY_RANGE[0] <= g <= GRAVITY_RANGE[1]:
            raise Rejected("Accelerometer values are implausible", f"mean magnitude {g:.1f} m/s², expected about 9.8")
    if name == "gps.csv":
        median_speed = statistics.median(speeds) if speeds else 0
        mean_speed = distance_m / span_s
        if median_speed > MAX_MEDIAN_SPEED_MPS or mean_speed > MAX_MEDIAN_SPEED_MPS:
            raise Rejected("Ride is too fast for a bike", f"median {median_speed:.1f} m/s, mean {mean_speed:.1f} m/s")
        report.distance_km = round(distance_m / 1000, 2)
        lats = [p[0] for p in points]
        lons = [p[1] for p in points]
        report.bbox = [round(min(lats), 3), round(min(lons), 3), round(max(lats), 3), round(max(lons), 3)]
        report.area_fraction = round(geo.area_fraction(points), 3)


def _distance_m(a, b) -> float:
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


MAP_ANNOTATION_ROWS = 5000


def check_map_annotations(data: bytes) -> int:
    """Returns the number of spots."""
    try:
        rows = list(csv.reader(io.StringIO(data.decode("utf-8"))))
    except (UnicodeDecodeError, csv.Error) as e:
        raise Rejected("Map annotations can't be read", str(e)[:200])
    rows = [r for r in rows if r]
    if not rows:
        raise Rejected("Map annotations are empty")
    _check_header("annotations.csv", rows[0])
    if len(rows) - 1 > MAP_ANNOTATION_ROWS:
        raise Rejected("Too many map annotations", f"{len(rows) - 1}")
    for i, row in enumerate(rows[1:], 1):
        if len(row) < 3 or len(row) > len(rows[0]):
            raise Rejected("Map annotations have rows of the wrong length", f"row {i}")
        lat = _number(row[1], "map annotations", "lat")
        lon = _number(row[2], "map annotations", "lon")
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise Rejected("Map annotation position is invalid", f"{lat}, {lon}")
    return len(rows) - 1
