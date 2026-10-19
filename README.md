# Smartphone Sensor Logger

This sensor logger app records GPS, accelerometer and microphone (1/3-octave band levels every 125 ms) to determine the quality of bicycle paths from various angles like the nature of underground and environmental noises.

Android 10 or newer. Recording keeps running with the phone in a pocket and the screen off.

## What it records

Each ride is one folder with three CSVs and a `meta.json`. Every CSV starts with `t_utc_ms`, a UTC timestamp in milliseconds from the phone clock, so the files (and data from other apps on the same phone) can be joined on time.

| File | Rate | Columns |
|---|---|---|
| `accel.csv` | 100 Hz | `ax, ay, az` in m/s², raw accelerometer including gravity |
| `gps.csv` | 1 Hz | `gps_time_ms, lat, lon, alt_m, speed_mps, bearing_deg, accuracy_m` |
| `sound.csv` | every 125 ms | `laeq_dbfs` (A-weighted level) and 26 band levels `band_50_dbfs` … `band_16000_dbfs` |
| `meta.json` | once | random ride id, phone model, sensor names, audio sample rate and source |

**Sound is never stored as audio.** Each 125 ms frame is turned into 26 band levels in memory and then discarded, so speech cannot be recovered from the files. Levels are in dBFS (0 dB = mean square of a full-scale signal) and are not calibrated. Compare them relative to each other and per phone, not as absolute dB(A). Bands below about 100 Hz are approximate (few FFT bins per band) and are dominated by wind noise while riding.

## Privacy on export

Tapping a ride (or **Export all rides**) writes a ZIP to `Downloads/BikeSensorLogger/` and opens the share sheet. Before export:

- Everything before the rider first leaves a 200 m radius around the start point, and everything after they last enter a 200 m radius around the end point, is cut from all three CSVs. This hides homes and workplaces. Rides that never leave the radius are left out.
- Folders are named by a random ride id; exact start and stop times are removed from `meta.json`.

The untrimmed data stays on the phone until the ride is deleted (long-press it in the list).

## Installing (for riders)

1. Download `app-debug.apk` from the repository's **Releases** page and open it. Allow installing from this source when Android asks.
2. Open **Bike Sensor Logger**, tap **Start ride** and allow location, microphone and notifications.
3. For reliable recording with the screen off, set the app's battery usage to **Unrestricted** (Settings → Apps → Bike Sensor Logger → Battery).
4. Put the phone in your pocket and ride. Tap **Stop ride** at the end.
5. Tap the ride and send the ZIP.

Other apps that use the microphone at the same time (e.g. NoiseCapture or a call) can block sound recording; the screen shows a warning if that happens.

## Building

```
./gradlew :core:test :app:assembleDebug
```

Needs JDK 17+ and the Android SDK (API 35). GitHub Actions runs the same on every push and pull request and uploads the APK as a build artifact; pushing a tag like `v0.1.0` publishes the APK as a release.

The signal processing (`core/`) is plain Kotlin and its tests run without Android:

- `ThirdOctaveAnalyzer`: Hann-windowed FFT of each 125 ms frame, summed into base-10 1/3-octave bands (50 Hz – 16 kHz) and an A-weighted total.
- `PrivacyTrim`: computes the time window kept on export.

`app/debug.keystore` is a throwaway debug key committed on purpose so every build can be installed over the previous one.
