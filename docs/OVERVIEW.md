# Bike Sensor Logger: project overview

*Last updated: 2026-10-23 · app version on `master`: 0.8.3*

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
| Readable ride names: UTC time of the first exported row plus 4 random characters, e.g. `20261021-1627Z-k3f9` | 🧪 Built, untested | 0.7.0 | Easier to handle in the analysis. Uses the first row *after* the privacy trim, so it reveals nothing the CSV timestamps don't already contain. The suffix is drawn when the ride starts, so re-exporting gives the same name. Rides recorded before 0.7.0 keep their old 8-character ids |
| Privacy trim at a random 150–250 m radius per ride end (radii stay on the phone) | ✅ Tested | 0.2.0 | Used for the 2026-10-21 export |
| "Export all rides" with progress window; skips unreadable rides | ✅ Tested | 0.6.0 | Fixes 0.5.0, where the export sometimes left no file |
| Untrimmed GPX export of the full track, for the rider's own use | ✅ Tested | 0.5.0 | Never meant for the project |
| Delete a ride on the phone | ✅ Tested | 0.1.0 | |

### Distribution and upload

| Feature | Status | Since | Notes |
|---|---|---|---|
| APK built by GitHub Actions on every push | ✅ Tested | 0.1.0 | Artifact "bike-sensor-logger-apk" |
| Release APK on the Releases page when a `v*` tag is pushed | 🧪 Built, untested | 0.1.0 | No tag pushed yet |
| Upload to the project server: HTTPS upload-only service on the VPS (`server/`, Docker behind nginx, `upload.velowind.de`) | 🧪 Built, untested | 0.8.0 | Tested locally with real rides, through nginx over HTTPS; not yet set up on the VPS. No password in the app. Uploads are privacy-trimmed exactly like the ZIP export. Setup: [server/deploy/SETUP.md](../server/deploy/SETUP.md) |
| Automatic upload on Wi-Fi (Android JobScheduler, unmetered network), with an "Upload now" button and a switch to turn it off | 🧪 Built, untested | 0.8.0 | On by default once the rider agrees to the consent text. Retries with back-off; "Upload now" asks before using mobile data. Each ride shows "uploaded", "waiting" or why the server didn't accept it |
| Open participation: the app makes a random install id and secret on first launch | 🧪 Built, untested | 0.8.0 | The server stores only a hash of the secret, so nobody can upload or delete under someone else's id. "Delete my data on the server" in Upload settings removes everything an install sent |
| Server checks on every upload: ZIP safety, untrimmed fields, columns, time window, sample rates, gravity, speed; rate limits per IP and install; free-space floor | 🧪 Built, untested | 0.8.0 | Can't catch realistic-looking fake rides, so outliers per install still need a look in the analysis. Every rejection is logged with details and the file kept for 30 days |
| Automatic promotion: rides fully in Germany, France or Switzerland go straight into the dataset; trusted installs from anywhere; the rest wait in quarantine | 🧪 Built, untested | 0.8.0 | An install becomes trusted after 5 automatically promoted rides. Untrusted and blocked installs are never promoted automatically. Admin commands: report, promote, trust, block, delete |
| Filter rides by location on the server | 💤 Deferred | | No area limit for now |
| Visual polish of the main screens | 🔜 Planned | | Last step before shipping to test riders |
| Rider instructions and consent text | 🔜 Planned | | A first consent text is in the app (0.8.0) and needs a check: who runs the project, a contact address, how long data is kept. Test riders are data subjects under GDPR |

### Later or not at all

| Feature | Status | Notes |
|---|---|---|
| "Looks like you're cycling, start recording?" notification (Play Services activity recognition) | 💤 Deferred | One tap starts the ride. Android doesn't allow the mic to start from the background, so a tap is needed |
| On-device feature extraction (e.g. per-second roughness instead of raw 100 Hz accel) | 💤 Deferred | The first test round keeps raw accel plus band levels; data reduction is the edge-AI part of the project |
| On-device classifier for surface and roughness | 💤 Deferred | Outlook; train offline first |
| "Cardio factor": how physically easy a stretch is to ride | 💤 Deferred | Analysis, not app logic. Best computed from physics per segment for a standard rider (gradient from the barometer, rolling resistance from the surface class, head/tailwind from weather and riding direction), and checked against the speeds and pedalling cadence (from accel/gyro) of real riders. E-bikes are left out via the bike type. No extra tick box: unusual load (trailer, luggage) is inferred from the motion data, e.g. slower speed and acceleration at the same gradients compared with the same rider's other rides. Heart rate from a Bluetooth chest strap would be the precise version (outlook) |
| Fully automatic start | ❌ Rejected | Would record without sound (background mic restriction) |
| Detect bike type automatically | ❌ Rejected | Too unreliable; asking is better |
| Passing trucks from the barometer | ❌ Rejected | The pressure pulse is at the sensor's noise level; the mic catches trucks better |
| Path width from GPS spread | ❌ Rejected | GPS error drifts slowly with the same sign for minutes; use OSM instead |
| iOS version | ❌ Rejected | Needs a paid developer account and TestFlight; too much effort for now |

## Open questions and tests

- **Sensor clock drift (found 2026-10-27):** on the test phone, rides recorded on 25 and 26 Oct had accelerometer, gyroscope and barometer times far from the GPS and sound times, so the privacy trim removed all of their rows (the first uploads were rejected for that). 0.8.1 corrects it while recording and realigns older rides on export, to within a few milliseconds of the ride start. Check that the re-uploaded rides line up, e.g. a bump visible in both accel and sound.

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
| 0.7.0 | Readable ride names in exports |
| 0.8.0 | Upload to the project server: consent, automatic upload on Wi-Fi, Upload now, upload settings with server-side delete |
| 0.8.1 | Sensor clock fix: accelerometer, gyroscope and barometer times are checked against the real time while recording; older rides with drifted times are realigned on export. Rides the server refused are sent again after an app update |
| 0.8.2 | "Upload now" runs as a background job with a progress notification, so it continues with the app closed |
| 0.8.3 | Opening the app no longer restarts a running automatic upload; "Upload now" takes over from a running upload instead of giving up |
