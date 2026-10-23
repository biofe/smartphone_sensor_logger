# Upload server

A small upload-only service that receives rides from the app, checks them, and files them into a quarantine or the dataset. It has no way to list or download data over the internet; the dataset is pulled over SSH.

Setup on a server (Docker, nginx, HTTPS) follows in a separate guide.

## How a ride gets in

1. The app sends the privacy-trimmed ride ZIP (the same file as "Send to the project", one ride per request).
2. The server checks the sender, the rate limits and free disk space, then the file itself: a safe ZIP with only the known files, `meta.json` without the fields the trim removes, the expected CSV columns, timestamps inside the ride's window, plausible sample rates, gravity, air pressure, sound levels and speed.
3. A ride that fails gets `400` with a short reason for the rider. The server logs the failed check with details and keeps the file in `rejected/` for 30 days (5 GB at most).
4. A ride that passes is promoted straight to the dataset when the whole track (99 % of it) lies in Germany, France or Switzerland, or when its install is trusted. Otherwise it waits in quarantine.
5. An install becomes trusted on its own after 5 automatically promoted rides. Installs marked untrusted or blocked are never promoted automatically.

## API

Every request except `/v1/health` carries `X-Install-Id: <uuid>` and `Authorization: Bearer <install secret>`. The app makes both on first launch; the first upload registers them, and the server keeps only a hash of the secret. Answers are JSON with `status` and, when refused, a `reason`.

| Request | Answers |
|---|---|
| `POST /v1/rides/<ride name>` with the ZIP as body, headers `X-Content-SHA256`, `X-App-Version` | `201` stored · `200` already had it · `400` rejected · `401` unknown secret · `403` blocked · `413` too large · `422` transfer damaged, send again · `429` slow down (`Retry-After`) · `503` storage full (`Retry-After`) |
| `PUT /v1/map-annotations` with `map_annotations.csv` as body | `200` stored (replaces the previous copy) · `400` · `401` · `403` · `413` · `429` |
| `DELETE /v1/install` | `200` everything of this install deleted |
| `GET /v1/health` | `200` with the lowest app version the server expects |

The app stops retrying a ride after `400`, `403` or `413`; everything else is retried later.

## Commands on the server

```
python -m velowind_upload.admin report            # quarantine, rejections of the last 7 days, installs
python -m velowind_upload.admin promote --all     # or: promote <install> [<ride>]
python -m velowind_upload.admin trust <install>   # also: untrust, block, unblock
python -m velowind_upload.admin delete <install>  # remove all data of an install
```

`<install>` can be the first 8 characters of the id, as the app shows it.

## Data folder

```
quarantine/<install>/<ride>.zip + .json   passed the checks, waiting
dataset/<install>/<ride>.zip + .json      promoted; the .json is the check report
set_aside/<install>/...                   quarantined rides of a blocked install
rejected/<install>/<time>-<ride>.zip      failed a check
map_annotations/<install>.csv
index.sqlite3                             installs, rides, rejections
```

IP addresses are kept only to limit new installs per network, and dropped after 7 days.

## Settings

All limits live in `velowind_upload/config.py` and can be changed with environment variables named `VELOWIND_<SETTING>`, e.g. `VELOWIND_DATA_DIR=/srv/velowind` or `VELOWIND_RIDES_PER_INSTALL_PER_DAY=50`.

## Development

```
cd server
python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest
VELOWIND_DATA_DIR=/tmp/velowind VELOWIND_MIN_FREE_BYTES=0 .venv/bin/uvicorn velowind_upload.main:app --port 8080
```

Country borders: Natural Earth 1:50m admin 0 (public domain), cut to Germany, France and Switzerland.
