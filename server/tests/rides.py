"""Synthetic rides shaped like the app's export, for the tests."""

import io
import json
import math
import zipfile

T0 = 1_792_589_602_000  # 2026-10-20


def ride_zip(
    name="20261020-1213Z-k3f9",
    minutes=10,
    start=(51.53, 9.93),  # Göttingen
    speed_mps=5.0,
    accel_hz=100,
    meta_extra=None,
    meta_drop=(),
    files_extra=None,
    accel_g=9.81,
    folder=None,
) -> bytes:
    duration_ms = int(minutes * 60_000)
    meta = {
        "ride_id": name,
        "app_version": "0.8.0",
        "device": "Test phone",
        "bike_type": "trekking",
        "privacy_trim": "random 150-250 m at each end",
        "kept_from_utc_ms": T0,
        "kept_to_utc_ms": T0 + duration_ms,
    }
    meta.update(meta_extra or {})
    for k in meta_drop:
        meta.pop(k)

    gps = ["t_utc_ms,gps_time_ms,lat,lon,alt_m,speed_mps,bearing_deg,accuracy_m"]
    lat, lon = start
    deg_per_m = 1 / (111_320 * math.cos(math.radians(lat)))
    for i in range(0, duration_ms + 1, 1000):
        gps.append(f"{T0 + i},{T0 + i},{lat:.6f},{lon + i / 1000 * speed_mps * deg_per_m:.6f},150.0,{speed_mps},90.0,4.0")
    step = 1000 / accel_hz
    accel = ["t_utc_ms,ax,ay,az"] + [
        f"{int(T0 + k * step)},0.1,{accel_g:.3f},0.2" for k in range(int(duration_ms / step) + 1)
    ]
    bands = ",".join(f"band_{f}_dbfs" for f in (50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000,
                                                1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000))
    sound = ["t_utc_ms,laeq_dbfs," + bands] + [
        f"{T0 + k * 125},-40.0," + ",".join(["-50.0"] * 26) for k in range(duration_ms // 125 + 1)
    ]
    files = {
        "meta.json": json.dumps(meta),
        "gps.csv": "\n".join(gps) + "\n",
        "accel.csv": "\n".join(accel) + "\n",
        "sound.csv": "\n".join(sound) + "\n",
        "annotations.csv": "t_utc_ms,lat,lon,accuracy_m,source,overall,surface,condition,width,traffic,noise,"
        "path_type,surface_detail,sounds,note\n"
        f'{T0 + 60_000},{lat},{lon},4.0,ride,good,asphalt,4,medium,little,quiet,,,,"a note, with comma"\n',
    }
    files.update(files_extra or {})
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for fname, content in files.items():
            if content is not None:
                z.writestr(f"{folder or name}/{fname}", content)
    return buf.getvalue()
