# Releases and app updates

Riders install the **release** app ("Bike Sensor Logger") from `https://apk.velowind.de/`. It checks `https://apk.velowind.de/latest.json` once a day and whenever it is opened (at most every 6 hours); **Upload settings → Check for updates** checks right away. When that file names a newer version, the app shows a notification and an **Install update** button. Tapping it downloads the APK, checks its SHA-256 and opens Android's installer, which asks the rider to confirm. The first time, Android also asks whether the app may install apps ("Allow from this source"). Rides and settings are kept. While a ride is recording, the update waits.

Android only installs an update that is signed with the same key as the installed app. The release key is private: it exists only on your own backup and as a GitHub secret. A file swapped on the server is therefore refused by Android, whatever `latest.json` says.

Test builds from pull requests and `master` (the "bike-sensor-logger-apk" artifact) are a separate app, **Bike Logger (dev)**, signed with the public debug key in the repo. They install next to the release app, never update themselves, and have their own rides and install id.

## One-time setup

### 1. Release key

Make the key on your own computer (needs Java 17+), or with Docker anywhere:

```
docker run --rm -it -v "$PWD":/k -w /k eclipse-temurin:17 \
  keytool -genkeypair -keystore velowind-release.jks -storetype PKCS12 \
  -alias velowind -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Velowind"
```

It asks for a password; use a long random one. Then:

- Keep `velowind-release.jks` and its password in two safe places (e.g. a password manager plus an offline copy). **If the key is lost, no installed app can be updated again**: every rider would have to uninstall (losing rides that weren't uploaded) and install anew.
- Delete the copy on any server once it's stored safely.

### 2. GitHub secrets

In the repository: Settings → Secrets and variables → Actions → New repository secret.

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | output of `base64 -w0 velowind-release.jks` (macOS: `base64 -i velowind-release.jks`) |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |

### 3. Server

Set up `apk.velowind.de` and the upload account as in [server/deploy/SETUP.md](../server/deploy/SETUP.md), step 7. Then add these secrets:

| Secret | Value |
|---|---|
| `VPS_HOST` | the VPS host name or IP address |
| `VPS_PORT` | only if SSH doesn't listen on port 22 |
| `VPS_SSH_KEY` | the private key `velowind-publish` made in step 7 (the whole file) |
| `VPS_KNOWN_HOSTS` | output of `ssh-keyscan -t ed25519 <host>` (add `-p <port>` if needed) |

Until `VPS_HOST` is set, releases are published on the Releases page only.

## Publishing a version

1. Raise `versionCode` (by 1) and `versionName` in `app/build.gradle.kts` and merge that into `master`.
2. Tag the merge commit with an annotated tag; its message becomes the release notes riders see in the update dialog:

   ```
   git tag -a v0.9.1 -m "Short note on what changed for riders"
   git push origin v0.9.1
   ```

The **Build** workflow then checks that the tag matches `versionName`, builds and signs the release APK, publishes it on the Releases page and copies it to the server: the versioned APK, `bike-sensor-logger.apk` (always the newest, for new riders) and `latest.json` last. Installed apps find the new version within a day.

`latest.json` looks like this:

```json
{
  "versionCode": 13,
  "versionName": "0.9.1",
  "apk": "bike-sensor-logger-0.9.1.apk",
  "sha256": "…",
  "notes": "Short note on what changed for riders"
}
```

To withdraw a broken version, put the previous `latest.json` back; apps that already updated stay on the new version, because Android doesn't install older versions over newer ones. The fix is a new, higher version.

Old APKs stay in `/srv/velowind/publish/apk/`; delete them by hand now and then.

## Switching from a test build to the release app

Apps installed before 0.9.0 were signed with the debug key, so the release app can't update them. Once, per phone:

1. Open the old app and make sure every ride shows "uploaded" (or tap **Export all rides** as a backup).
2. Uninstall the old app. This deletes its rides from the phone.
3. Install `https://apk.velowind.de/` and agree to the upload again.

The new install has a new install id, so the server treats it as a new phone. Trust it with `velowind trust <short id>` (shown under Upload settings), or let it earn trust with 5 rides.
