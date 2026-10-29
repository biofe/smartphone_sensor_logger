import pytest

from velowind_upload.checks import Rejected, check_map_annotations, check_ride_zip
from velowind_upload.config import Settings

from .rides import T0, ride_zip

NAME = "20261020-1213Z-k3f9"


def check(tmp_path, data, name=NAME):
    p = tmp_path / "ride.zip"
    p.write_bytes(data)
    return check_ride_zip(p, name, Settings(data_dir=tmp_path), now_ms=T0 + 86_400_000)


def rejected(tmp_path, data, name=NAME) -> str:
    with pytest.raises(Rejected) as e:
        check(tmp_path, data, name)
    return e.value.reason


def test_good_ride_passes_with_report(tmp_path):
    r = check(tmp_path, ride_zip())
    assert r.duration_s == 600
    assert r.distance_km == pytest.approx(3.0, abs=0.05)
    assert r.rates_hz["accel.csv"] == pytest.approx(100, abs=1)
    assert r.area_fraction == 1.0
    assert r.annotations == 1


def test_legacy_ride_id_passes(tmp_path):
    check(tmp_path, ride_zip(name="76b63efb"), name="76b63efb")


def test_ride_outside_area_passes_but_says_so(tmp_path):
    assert check(tmp_path, ride_zip(start=(48.21, 16.37))).area_fraction == 0.0  # Vienna


@pytest.mark.parametrize(
    "data, reason",
    [
        (b"not a zip", "Not a ZIP file"),
        (ride_zip(folder="other"), "Unexpected files in the ZIP"),
        (ride_zip(files_extra={"audio.wav": "RIFF"}), "Unexpected files in the ZIP"),
        (ride_zip(files_extra={"gps.csv": None}), "Ride is missing files"),
        (ride_zip(meta_extra={"trim_start_m": 180.0}), "Ride was not privacy-trimmed"),
        (ride_zip(meta_extra={"ride_id": "20261020-1213Z-zzzz"}), "Ride name doesn't match meta.json"),
        (ride_zip(meta_drop=("kept_to_utc_ms",)), "meta.json lacks the kept time window"),
        (ride_zip(minutes=1), "Ride duration is implausible"),
        (ride_zip(meta_extra={"kept_from_utc_ms": 1_600_000_000_000}), "Ride time is implausible"),
        (ride_zip(speed_mps=30), "Ride is too fast for a bike"),
        (ride_zip(accel_hz=10), "accel.csv sample rate is implausible"),
        (ride_zip(files_extra={"accel.csv": "t_utc_ms,ax,ay,az\n"}), "accel.csv has no data inside the ride's time window"),
        (ride_zip(accel_g=0.0), "Accelerometer values are implausible"),
        (ride_zip(files_extra={"accel.csv": "t_utc_ms,x,y,z\n"}), "accel.csv has unexpected columns"),
        (ride_zip(files_extra={"gyro.csv": f"t_utc_ms,gx,gy,gz\n{T0},1,2\n"}), "gyro.csv has rows of the wrong length"),
        (ride_zip(files_extra={"pressure.csv": f"t_utc_ms,pressure_hpa\n{T0},abc\n"}), "pressure.csv has a value that isn't a number"),
        (ride_zip(files_extra={"pressure.csv": f"t_utc_ms,pressure_hpa\n{T0 - 3_600_000},1000\n"}), "pressure.csv has rows outside the ride's time window"),
        (ride_zip(files_extra={"pressure.csv": f"t_utc_ms,pressure_hpa\n{T0 + 2000},1000\n{T0 + 1000},1000\n"}), "pressure.csv timestamps go backwards"),
    ],
)
def test_bad_rides_are_rejected(tmp_path, data, reason):
    assert rejected(tmp_path, data) == reason


def test_invalid_ride_name(tmp_path):
    assert rejected(tmp_path, ride_zip(name="../etc"), name="../etc") == "Invalid ride name"


def test_zip_bomb_is_rejected(tmp_path):
    bomb = ride_zip(files_extra={"gyro.csv": "t_utc_ms,gx,gy,gz\n" + "0" * 50_000_000})
    assert rejected(tmp_path, bomb) == "ZIP entry unpacks too large"


def test_map_annotations():
    header = "t_utc_ms,lat,lon,accuracy_m,source,overall\n"
    assert check_map_annotations((header + f"{T0},51.5,9.9,,map,good\n").encode()) == 1
    with pytest.raises(Rejected):
        check_map_annotations((header + f"{T0},151.5,9.9,,map,good\n").encode())
    with pytest.raises(Rejected):
        check_map_annotations(b"lat,lon\n1,2\n")


LABELS_HEADER = "t_utc_ms,lat,lon,accuracy_m,source,category,value,undone\n"


def test_labels_pass_with_count(tmp_path):
    labels = LABELS_HEADER + (
        f"{T0 + 90_000},51.5300000,9.9310000,4.0,live,surface,bumpy,\n"
        f"{T0 + 30_000},,,,live,noise,quiet,1\n"  # before the first fix, undone, out of order
        f"{T0 + 120_000},51.5300000,9.9320000,,later,width,narrow,\n"
    )
    assert check(tmp_path, ride_zip(files_extra={"labels.csv": labels})).labels == 3


@pytest.mark.parametrize(
    "row, reason",
    [
        (f"{T0 + 9_000_000},51.53,9.93,4.0,live,surface,bumpy,", "labels.csv has rows outside the ride's time window"),
        (f"{T0 + 60_000},151.53,9.93,4.0,live,surface,bumpy,", "Label position is invalid"),
        (f"{T0 + 60_000},51.53,9.93,4.0,radio,surface,bumpy,", "labels.csv has an invalid row"),
        (f"{T0 + 60_000},51.53,9.93,4.0,live,surface,Bumpy!,", "labels.csv has an invalid row"),
        (f"{T0 + 60_000},51.53,9.93,4.0,live,surface,bumpy,yes", "labels.csv has an invalid row"),
        (f"{T0 + 60_000},51.53,9.93,4.0,live,surface", "labels.csv has rows of the wrong length"),
    ],
)
def test_bad_labels_are_rejected(tmp_path, row, reason):
    assert rejected(tmp_path, ride_zip(files_extra={"labels.csv": LABELS_HEADER + row + "\n"})) == reason


def test_labels_with_wrong_header_are_rejected(tmp_path):
    data = ride_zip(files_extra={"labels.csv": "t_utc_ms,lat,lon,label\n"})
    assert rejected(tmp_path, data) == "labels.csv has unexpected columns"
