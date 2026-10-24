"""The HTTP API the app talks to. Run behind nginx: uvicorn velowind_upload.main:app --host 0.0.0.0 --port 8080"""

import hashlib
import ipaddress
import logging
import re
import time
import uuid
from collections import defaultdict, deque

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from . import checks
from .config import Settings
from .store import Store

log = logging.getLogger("velowind_upload")

SECRET = re.compile(r"^[A-Za-z0-9_-]{43,128}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def _answer(code: int, status: str, reason: str | None = None, **extra) -> JSONResponse:
    body = {"status": status, **extra}
    if reason:
        body["reason"] = reason
    headers = {"Retry-After": str(extra["retry_after_s"])} if "retry_after_s" in extra else None
    return JSONResponse(body, status_code=code, headers=headers)


class _Refused(Exception):
    def __init__(self, response: JSONResponse):
        self.response = response


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    store = Store(settings)
    store.cleanup()
    app = FastAPI(title="Velowind ride upload", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store = store
    hits: dict[str, deque] = defaultdict(deque)
    last_cleanup = [time.time()]

    def client_ip(request: Request) -> str:
        peer = request.client.host if request.client else ""
        try:
            from_proxy = any(ipaddress.ip_address(peer) in n for n in settings.proxy_networks)
        except ValueError:
            from_proxy = False
        forwarded = request.headers.get("x-real-ip", "")
        return forwarded if from_proxy and forwarded else peer

    def limit_ip(ip: str):
        now = time.time()
        q = hits[ip]
        while q and q[0] < now - 3600:
            q.popleft()
        if len(q) >= settings.requests_per_ip_per_hour:
            raise _Refused(_answer(429, "slow_down", "Too many requests", retry_after_s=int(q[0] + 3600 - now) + 1))
        q.append(now)
        if len(hits) > 10_000:  # forget idle addresses
            for k in [k for k, v in hits.items() if not v or v[-1] < now - 3600]:
                del hits[k]

    def authenticate(request: Request, ip: str, register: bool):
        """Returns the install row, or None for an unknown install when not registering."""
        install_id = request.headers.get("x-install-id", "")
        auth = request.headers.get("authorization", "")
        secret = auth[7:] if auth.startswith("Bearer ") else ""
        try:
            if str(uuid.UUID(install_id)) != install_id.lower():
                raise ValueError
        except ValueError:
            raise _Refused(_answer(401, "unauthorized", "Missing or invalid install id"))
        install_id = install_id.lower()
        if not SECRET.match(secret):
            raise _Refused(_answer(401, "unauthorized", "Missing or invalid install secret"))
        row = store.install(install_id)
        if row is None:
            if not register:
                return install_id, None
            if store.registrations_from(ip, 86_400) >= settings.new_installs_per_ip_per_day:
                raise _Refused(_answer(429, "slow_down", "Too many new installs from this network", retry_after_s=3600))
            store.register(install_id, secret, ip)
            log.info("registered install %s", install_id[:8])
            return install_id, store.install(install_id)
        if not store.secret_matches(row, secret):
            raise _Refused(_answer(401, "unauthorized", "Wrong install secret"))
        store.touch(install_id)
        return install_id, row

    def guard(request: Request, register: bool):
        if time.time() - last_cleanup[0] > 3600:
            last_cleanup[0] = time.time()
            store.cleanup()
        ip = client_ip(request)
        limit_ip(ip)
        install_id, row = authenticate(request, ip, register)
        return install_id, row

    @app.exception_handler(_Refused)
    async def refused(request: Request, exc: _Refused):
        return exc.response

    @app.get("/v1/health")
    async def health():
        return {"status": "ok", "min_app_version": settings.min_app_version}

    @app.post("/v1/rides/{ride_name}")
    async def upload_ride(ride_name: str, request: Request):
        if store.free_bytes() < settings.min_free_bytes:
            log.warning("refusing uploads: disk nearly full")
            return _answer(503, "busy", "Server storage is full, try later", retry_after_s=6 * 3600)
        length = request.headers.get("content-length")
        if length and length.isdigit() and int(length) > settings.max_ride_bytes:
            return _answer(413, "rejected", "Ride is too large")
        install_id, row = guard(request, register=True)
        app_version = request.headers.get("x-app-version", "")[:20]
        if row["trust"] == "blocked":
            return _answer(403, "blocked", "This install may not upload")
        try:
            checks.check_ride_name(ride_name)
        except checks.Rejected as e:
            store.reject(install_id, None, app_version, 400, e.reason, e.details, None, None)
            return _answer(400, "rejected", e.reason)
        claimed = request.headers.get("x-content-sha256", "").lower()
        if not SHA256.match(claimed):
            return _answer(400, "rejected", "Missing X-Content-SHA256 header")
        if store.ride(install_id, ride_name) is not None:
            return _answer(200, "duplicate")
        rides, size = store.usage_last_day(install_id)
        if rides >= settings.rides_per_install_per_day or size >= settings.bytes_per_install_per_day:
            return _answer(429, "slow_down", "Daily upload limit reached", retry_after_s=6 * 3600)

        tmp = store.root / "tmp" / f"{uuid.uuid4().hex}.zip"
        digest = hashlib.sha256()
        received = 0
        try:
            with tmp.open("wb") as f:
                async for chunk in request.stream():
                    received += len(chunk)
                    if received > settings.max_ride_bytes:
                        raise _Refused(_answer(413, "rejected", "Ride is too large"))
                    digest.update(chunk)
                    f.write(chunk)
        except _Refused as e:
            tmp.unlink(missing_ok=True)
            store.reject(install_id, ride_name, app_version, 413, "Ride is too large", f"over {settings.max_ride_bytes} bytes", None, received)
            return e.response
        except Exception:
            tmp.unlink(missing_ok=True)
            raise
        sha = digest.hexdigest()
        if sha != claimed:
            # A cut-off or damaged transfer: the app sends the ride again
            tmp.unlink(missing_ok=True)
            log.info("checksum mismatch %s/%s after %d bytes", install_id[:8], ride_name, received)
            return _answer(422, "retry", "Upload was incomplete or damaged, please send again")

        try:
            report = await run_in_threadpool(checks.check_ride_zip, tmp, ride_name, settings)
        except checks.Rejected as e:
            store.reject(install_id, ride_name, app_version, 400, e.reason, e.details, tmp, received)
            log.info("rejected %s/%s: %s (%s)", install_id[:8], ride_name, e.reason, e.details)
            return _answer(400, "rejected", e.reason)
        except Exception:
            tmp.unlink(missing_ok=True)
            raise

        trust = store.install(install_id)["trust"]
        promote = trust == "trusted" or (trust == "new" and report.area_fraction >= settings.area_fraction)
        state = store.add_ride(install_id, ride_name, tmp, sha, received, report, promote, "auto")
        log.info("stored %s/%s (%d bytes) in %s", install_id[:8], ride_name, received, state)
        return _answer(201, "stored")

    @app.put("/v1/map-annotations")
    async def upload_map_annotations(request: Request):
        install_id, row = guard(request, register=True)
        if row["trust"] == "blocked":
            return _answer(403, "blocked", "This install may not upload")
        data = b""
        async for chunk in request.stream():
            data += chunk
            if len(data) > settings.max_map_annotation_bytes:
                return _answer(413, "rejected", "Map annotations are too large")
        try:
            spots = checks.check_map_annotations(data)
        except checks.Rejected as e:
            store.reject(install_id, None, request.headers.get("x-app-version", "")[:20], 400, e.reason, e.details, None, len(data))
            return _answer(400, "rejected", e.reason)
        store.save_map_annotations(install_id, data)
        log.info("stored %d map annotations of %s", spots, install_id[:8])
        return _answer(200, "stored")

    @app.delete("/v1/install")
    async def delete_install(request: Request):
        install_id, row = guard(request, register=False)
        if row is None:
            return _answer(200, "deleted", rides=0)
        removed = store.delete_install(install_id)
        log.info("deleted install %s on request (%d rides)", install_id[:8], removed)
        return _answer(200, "deleted", rides=removed)

    return app


def __getattr__(name):
    # `uvicorn velowind_upload.main:app` builds the app from the environment on first access
    if name == "app":
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
        globals()["app"] = create_app()
        return globals()["app"]
    raise AttributeError(name)
