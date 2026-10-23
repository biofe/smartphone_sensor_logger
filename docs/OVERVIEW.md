# Bike Sensor Logger: project overview

*Last updated: 2026-10-23 · app version on `master`: 0.6.0*

This page is the living overview of the logger: why it exists, what the finished app should do, and where each feature stands. Update the status tables whenever a feature changes state, and bump the date above. The [README](../README.md) stays the reference for file formats, installing and building.

## Purpose

The logger collects data for a **crowdsourced map of cycle path quality**. Each ride records how rough the surface is, how loud the surroundings are, and where and when the ride happened. Later steps outside the app add weather and map-match the rides to OpenStreetMap ways, so the map can show quality by surface, noise, time of day and weather.

Ready-made apps didn't fit. Sensor Logger records loudness or raw audio but no frequency bands, and NoiseCapture has no accelerometer and can't share the microphone with another app. The custom logger records everything in one app, on one clock, and is built for privacy from the start:

- **No audio is stored.** Sound becomes 26 third-octave band levels every 125 ms on the phone, so speech can't be recovered.
- **Homes and workplaces stay hidden.** Exports cut each ride's start and end at a random 150–250 m radius, and rides get random ids.
- **Ground truth from the rider.** A short poll at a spot (or later on a map) labels surface, condition, width, traffic and noise, which the classifier learns from.

The app is Android only (Android 10+). iOS is out of scope for now.

## Status legend

| Status | Meaning |
|---|---|
| ✅ Tested | Built and confirmed working in at least one real ride |
| 🧪 Built, untested | In the app on `master`, but not yet run on a phone (or not yet seen working) |
| 🔜 Planned | Wanted for the version test riders get; not built yet |
| 💤 Deferred | Good idea, but parked until after the first test round |
| ❌ Rejected | Decided against, with the reason |

## Features and their state

### Recording

| Feature | Status | Since | Notes |
|---|---|---|---|
| GPS track at 1 Hz | ✅ Tested | 0.1.0 | Median accuracy ~4.6 m on the first ride, no gaps |
| Accelerometer (raw, with gravity) | ✅ Tested | 0.1.0 | Requested 100 Hz, actual ~118 Hz on a Pixel 7a |
| Sound: LAeq + 26 third-octave bands (50 Hz–16 kHz) every 125 ms | ✅ Tested | 0.1.0 | Uncalibrated dBFS. Uses the `UNPROCESSED` mic where available, else `VOICE_RECOGNITION` (Pixel 7a) |
| Recording in a pocket with the screen off (foreground service) | ✅ Tested | 0.1.0 | Battery usage must be set to Unrestricted |
| Warning when another app holds the microphone | 🧪 Built, untested | 0.1.0 | Don't run alongside NoiseCapture |
| Gyroscope | ✅ Tested | 0.3.0 | Shows mount swing (~7.8 Hz on the long-arm handlebar mount), cornering |
| Barometer at 10 Hz | ✅ Tested | 0.4.0 | Tracks GPS altitude (r = 0.99) without drift; noisy second to second |
| Bike selection: pick bikes on first launch, add/remove (at least one), one start button per bike, bike type in `meta.json` | ✅ Tested | 0.5.0 | Only the type leaves the phone, never the bike's name |
| Auto-stop after 10 min within 50 m | ✅ Tested | 0.2.0 | Confirmed 2026-10-22; standing time is left out of the export |

### Ground truth (annotation)

| Feature | Status | Since | Notes |
|---|---|---|---|
| "Annotate this spot" poll while recording | ✅ Tested | 0.3.0 | 15 annotations on the 2026-10-21 handlebar ride (older detailed poll) |
| Short poll: six one-tap questions (overall, surface, condition, width, motor traffic, noise) | ✅ Tested | 0.6.0 | The default for test riders |
| "Detailed poll" tick box: type of way, exact surface, sounds, note | ✅ Tested | 0.6.0 | Appends the 0.4–0.5 questions to the short poll |
| Type of way incl. "cycle street" and "shared footway (in town)"; width = the space you may ride in your direction | ✅ Tested | 0.5.0 | Country shared paths count as separate cycle path |
| Annotate on the map: OSM view of own tracks and earlier answers, tap a spot to rate it | ✅ Tested | 0.6.0 | Exported as `map_annotations.csv`, minus spots within 250 m of any ride's start or end. Tiles come from tile.openstreetmap.org |
| Wind question in the short poll (calm / some / strong) | 🔜 Planned | | Labels wind noise for the sound classifier and helps the effort estimate. Wind speed and direction also come from the weather join, so the poll answer mostly checks what the rider felt and heard |
| Quick marks while riding: big rating buttons on the recording notification (e.g. good / bad / mark), usable without unlocking | 🔜 Planned | | Saves the position and time without stopping; the full poll for a mark can be filled in later on the map. Only for a mounted phone and a short tap at safe moments: German traffic law (§ 23 StVO) allows only brief operation of a phone that isn't held |

### Export and privacy

| Feature | Status | Since | Notes |
|---|---|---|---|
| ZIP export per ride to `Downloads/BikeSensorLogger/` via share sheet | ✅ Tested | 0.1.0 | |
| Random ride ids, start/stop times removed from `meta.json` | ✅ Tested | 0.1.0 | |
| Privacy trim at a random 150–250 m radius per ride end (radii stay on the phone) | ✅ Tested | 0.2.0 | Used for the 2026-10-21 export |
| "Export all rides" with progress window; skips unreadable rides | ✅ Tested | 0.6.0 | Fixes 0.5.0, where the export sometimes left no file |
| Untrimmed GPX export of the full track, for the rider's own use | ✅ Tested | 0.5.0 | Never meant for the project |
| Delete a ride on the phone | ✅ Tested | 0.1.0 | |

### Distribution and upload

| Feature | Status | Since | Notes |
|---|---|---|---|
| APK built by GitHub Actions on every push | ✅ Tested | 0.1.0 | Artifact "bike-sensor-logger-apk" |
| Release APK on the Releases page when a `v*` tag is pushed | 🧪 Built, untested | 0.1.0 | No tag pushed yet |
| Upload to the project server (VPS): HTTPS upload-only endpoint | 🔜 Planned | | Needed before test riders start. Never hard-code a password (no HiDrive login in the app). Uploads are privacy-trimmed exactly like the ZIP export |
| Automatic upload on Wi-Fi (Android WorkManager, unmetered network), with a manual "Upload now" button and a switch to turn it off | 🔜 Planned | | Retries by itself after a lost connection; rides are uploaded only after they are finished and trimmed |
| Open participation: no personal codes; the app makes a random install id on first launch and sends it with each upload | 🔜 Planned | | Replaces per-rider codes. The id lets a rider ask for deletion of their data and lets the server block one install that misbehaves, without knowing who anyone is |
| Server checks on every upload: file format and columns, size limit, plausible values (rates, speeds, coordinates), rate limit per install and IP, quarantine before data enters the dataset | 🔜 Planned | | Stops broken files, spam and full disks. Can't catch realistic-looking fake rides, so outliers per install still need a look in the analysis |
| Visual polish of the main screens | 🔜 Planned | | Last step before shipping to test riders |
| Rider instructions and consent text | 🔜 Planned | | Test riders are data subjects under GDPR |

### Later or not at all

| Feature | Status | Notes |
|---|---|---|
| "Looks like you're cycling, start recording?" notification (Play Services activity recognition) | 💤 Deferred | One tap starts the ride. Android doesn't allow the mic to start from the background, so a tap is needed |
| On-device feature extraction (e.g. per-second roughness instead of raw 100 Hz accel) | 💤 Deferred | The first test round keeps raw accel plus band levels; data reduction is the edge-AI part of the project |
| On-device classifier for surface and roughness | 💤 Deferred | Outlook; train offline first |
| "Cardio factor": how physically easy a stretch is to ride | 💤 Deferred | Analysis, not app logic. Best computed from physics per segment for a standard rider (gradient from the barometer, rolling resistance from the surface class, head/tailwind from weather and riding direction), and checked against the speeds and pedalling cadence (from accel/gyro) of real riders. E-bikes are left out via the bike type. The app would only need a "trailer / heavy load" tick box at ride start. Heart rate from a Bluetooth chest strap would be the precise version (outlook) |
| Fully automatic start | ❌ Rejected | Would record without sound (background mic restriction) |
| Detect bike type automatically | ❌ Rejected | Too unreliable; asking is better |
| Passing trucks from the barometer | ❌ Rejected | The pressure pulse is at the sensor's noise level; the mic catches trucks better |
| Path width from GPS spread | ❌ Rejected | GPS error drifts slowly with the same sign for minutes; use OSM instead |
| iOS version | ❌ Rejected | Needs a paid developer account and TestFlight; too much effort for now |

## Open questions and tests

- **Mic drop at annotation stops:** on 2026-10-21 the sound fell to −55 to −65 dBFS at every annotation stop, against −42 at a stop without one. Test a stop where you open the app without annotating, to see whether app use mutes the mic.
- **Rigid open handlebar mount** vs. the long-arm closed case (arm swing sits inside the 3–8 Hz roughness band).
- **Furry wind cover** for the microphone: one test ride.

## Test rides so far

| Date | App | Mount | What it showed |
|---|---|---|---|
| 2026-10-19 | 0.1.0 | Pocket | No sensor gaps; road sweeper clearly audible; gravel inconclusive, slab joints masked by pedalling |
| 2026-10-20 | 0.1.0 | Pocket | Four rides; railroad crossing visible; one ride left running ~40 min (led to auto-stop) |
| 2026-10-21 | 0.5.0 | Handlebar, long arm, closed rain case | Gravel at km 5.0–6.7 clear in accel (2–3×) and sound (8 kHz +13 dB); barometer matches GPS; mic quieter after the case was re-seated |

## Version history

| Version | Main changes |
|---|---|
| 0.1.0 | GPS, accelerometer, third-octave sound bands; ZIP export with privacy trim |
| 0.2.0 | Auto-stop; random 150–250 m trim radius per ride end |
| 0.3.0 | Gyroscope; "Annotate this spot" poll |
| 0.4.0 | Barometer; type of way and width in the poll |
| 0.5.0 | Bikes with one start button each; untrimmed GPX export; cycle street |
| 0.6.0 | Short poll plus optional detailed poll; annotate on the map; reliable Export all |
