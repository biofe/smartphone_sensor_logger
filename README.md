# Smartphone Sensor Logger

This sensor logger app records GPS, accelerometer, gyroscope, barometer and microphone (1/3-octave band levels every 125 ms, never raw audio) to determine the quality of bicycle paths from various angles, such as the surface and the surrounding noise. Riders can add ground truth with a short poll, either at the spot or later on a map.

Android 10 or newer. Recording keeps running with the phone in a pocket and the screen off.

For the project's purpose, planned features and the state of each one, see [docs/OVERVIEW.md](docs/OVERVIEW.md).

## What it records

Each ride is one folder with one CSV per sensor and a `meta.json`. Every CSV starts with `t_utc_ms`, a UTC timestamp in milliseconds from the phone clock, so the files (and data from other apps on the same phone) can be joined on time.

| File | Rate | Columns |
|---|---|---|
| `accel.csv` | 100 Hz | `ax, ay, az` in m/s², raw accelerometer including gravity |
| `gyro.csv` | 100 Hz | `gx, gy, gz` in rad/s, rotation rate around the phone's axes (if the phone has a gyroscope) |
| `pressure.csv` | 10 Hz | `pressure_hpa`, air pressure (if the phone has a barometer); gives elevation changes more precisely than GPS |
| `gps.csv` | 1 Hz | `gps_time_ms, lat, lon, alt_m, speed_mps, bearing_deg, accuracy_m` |
| `sound.csv` | every 125 ms | `laeq_dbfs` (A-weighted level) and 26 band levels `band_50_dbfs` … `band_16000_dbfs` |
| `labels.csv` | when the rider taps a poll button during the ride | `t_utc_ms`, `lat, lon, accuracy_m` of the last GPS fix (empty before the first fix), `source` (`live`), `category` (`noise`, `surface`, `width`, `comfort`), `value` (e.g. `far_traffic`, `bumpy`), `undone` (`1` if Undo was pressed) |
| `annotations.csv` | when the rider fills in the poll | `lat, lon, accuracy_m` of the last GPS fix, `source`, `overall`, `surface`, `condition`, `width`, `traffic`, `noise`, and with the detailed poll `path_type`, `surface_detail`, `sounds` (`;`-separated), `note`. Rides from 0.3–0.5 have the older columns. |
| `meta.json` | once | ride name (see below), bike type, phone model, sensor names, audio sample rate and source |

**Sound is never stored as audio.** Each 125 ms frame is turned into 26 band levels in memory and then discarded, so speech cannot be recovered from the files. Levels are in dBFS (0 dB = mean square of a full-scale signal) and are not calibrated. Compare them relative to each other and per phone, not as absolute dB(A). Bands below about 100 Hz are approximate (few FFT bins per band) and are dominated by wind noise while riding.

## Privacy on export

Tapping a ride and choosing **Send to the project** (or **Export all rides**) writes a ZIP to `Downloads/BikeSensorLogger/` and opens the share sheet. Before export:

- Everything before the rider first leaves a radius around the start point, and everything after they last enter a radius around the end point, is cut from all CSVs, annotations included. This hides homes and workplaces. Each radius is drawn at random between 150 and 250 m when the ride starts, separately for start and end, and is not exported, so the cut-off point doesn't reveal how far away the start was. Rides that never leave the radius are left out.
- Folders are named like `20261021-1627Z-k3f9`: the UTC minute of the first row kept after the trim plus 4 random characters, so the name reveals nothing the CSV timestamps don't. Rides recorded before 0.7.0 keep their random 8-character id. Exact start and stop times are removed from `meta.json`.

The untrimmed data stays on the phone until the ride is deleted (tap it, then **Delete**).

## Upload to the project

From 0.8.0 the app can send rides to the project server itself instead of the share sheet. It asks once whether you agree; after that, every finished ride is uploaded on Wi-Fi in the background, trimmed exactly like the ZIP above. The ride list shows for each ride whether it was uploaded, is waiting, or why the server didn't accept it. Uploaded rides stay on the phone.

- **Upload now** sends waiting rides right away, and asks first when you're not on Wi-Fi.
- **Settings → Upload** and **Privacy and storage** turn automatic upload off, shows what is sent, shows this phone's short id, and has **Delete my data on the server**, which removes everything this phone sent.

The phone identifies itself with a random id and secret made on first launch; there are no accounts or passwords. The server side is in [`server/`](server/README.md).

**Export my track as GPX** writes the full, untrimmed GPS track to `Downloads/BikeSensorLogger/` for your own use (Strava, Komoot, OsmAnd). It is not privacy-trimmed and is not meant for the project.

## Installing (for riders)

1. Open `https://apk.velowind.de/` on the phone to download the app, and open the file. Allow installing from this source when Android asks. The app then updates itself: it asks before installing a new version.
2. Open **Velowind** and pick the bikes you ride (change them later under **Settings → My bikes**). Tap **Start ride** for the bike you're on and allow location, microphone and notifications.
3. For reliable recording with the screen off, set the app's battery usage to **Unrestricted** (Settings → Apps → Velowind → Battery).
4. Put the phone in your pocket and ride. Tap **Stop ride** at the end. If you forget, recording stops by itself after 10 minutes without moving (more than 50 m), and the standing time is left out of the export. Waits at traffic lights are far shorter and don't stop it.

To record ground truth, stop, open the app and tap **Annotate this spot** while the ride is recording. The poll has six one-tap questions (overall, surface, condition, width, motor traffic, noise) and is saved with your position. Ticking **Detailed poll** adds type of way, exact surface, what you hear and a note.

**Annotate on the map** shows your recorded tracks and earlier answers on OpenStreetMap; tap any spot to rate it without going there. These spots are exported as `map_annotations.csv`, except those within 250 m of any ride's start or end. Map tiles are loaded from tile.openstreetmap.org, which sees the map area you look at.
5. Rides upload by themselves on Wi-Fi once you've agreed. Without upload, tap the ride and send the ZIP.

Other apps that use the microphone at the same time (e.g. NoiseCapture or a call) can block sound recording; the screen shows a warning if that happens.

## Building

```
./gradlew :core:test :app:assembleDebug
```

Needs JDK 17+ and the Android SDK (API 35). GitHub Actions runs the same on every push and pull request and uploads the test APK ("Velowind (dev)", a separate app next to the release one) as a build artifact. Pushing a tag like `v0.9.0` builds the signed release APK, publishes it as a release and copies it to the update server; see [docs/RELEASES.md](docs/RELEASES.md).

The signal processing (`core/`) is plain Kotlin and its tests run without Android:

- `ThirdOctaveAnalyzer`: Hann-windowed FFT of each 125 ms frame, summed into base-10 1/3-octave bands (50 Hz – 16 kHz) and an A-weighted total.
- `PrivacyTrim`: draws the random trim radii and computes the time window kept on export.
- `StopDetector`: decides when the rider has stopped moving long enough to end the recording.

`app/debug.keystore` is a throwaway debug key committed on purpose so every test build can be installed over the previous one. Release builds use a private key that only GitHub Actions has.
