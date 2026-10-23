import ipaddress
import os
from dataclasses import dataclass, field
from pathlib import Path


def _env(name: str, default):
    raw = os.environ.get("VELOWIND_" + name)
    if raw is None:
        return default
    return type(default)(raw)


@dataclass
class Settings:
    """Limits and paths. Every value can be overridden by an environment variable VELOWIND_<NAME>."""

    data_dir: Path = Path("/data")
    min_app_version: str = "0.8.0"

    max_ride_bytes: int = 100 * 1024 * 1024
    max_unpacked_bytes: int = 600 * 1024 * 1024
    max_unpack_ratio: float = 20.0
    max_map_annotation_bytes: int = 1024 * 1024
    min_free_bytes: int = 5 * 1024**3

    requests_per_ip_per_hour: int = 60
    rides_per_install_per_day: int = 30
    bytes_per_install_per_day: int = 1024**3
    new_installs_per_ip_per_day: int = 5

    auto_trust_after: int = 5
    area_fraction: float = 0.99
    rejected_keep_days: int = 30
    rejected_max_bytes: int = 5 * 1024**3
    ip_keep_days: int = 7

    # nginx in front of the container: only trust X-Real-IP from these addresses
    trusted_proxies: str = "127.0.0.1/32,::1/128,172.16.0.0/12"

    proxy_networks: list = field(init=False, repr=False)

    def __post_init__(self):
        self.proxy_networks = [ipaddress.ip_network(n.strip()) for n in self.trusted_proxies.split(",") if n.strip()]

    @classmethod
    def from_env(cls) -> "Settings":
        defaults = cls()
        values = {}
        for name in cls.__dataclass_fields__:
            if name == "proxy_networks":
                continue
            default = getattr(defaults, name)
            values[name] = Path(_env(name.upper(), str(default))) if isinstance(default, Path) else _env(name.upper(), default)
        return cls(**values)
