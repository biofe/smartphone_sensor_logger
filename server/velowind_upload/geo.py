"""Is a track inside Germany, France or Switzerland? Borders from Natural Earth 1:50m (public domain)."""

import json
import math
from functools import lru_cache
from pathlib import Path

_BORDERS = Path(__file__).parent / "data" / "countries.geojson"


@lru_cache(maxsize=1)
def _polygons():
    """Each polygon as (bounding box, outer ring, holes)."""
    out = []
    for feature in json.loads(_BORDERS.read_text())["features"]:
        for outer, *holes in feature["geometry"]["coordinates"]:
            xs = [p[0] for p in outer]
            ys = [p[1] for p in outer]
            out.append(((min(xs), min(ys), max(xs), max(ys)), outer, holes))
    return out


def _in_ring(lon: float, lat: float, ring) -> bool:
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def in_area(lat: float, lon: float) -> bool:
    for (x0, y0, x1, y1), outer, holes in _polygons():
        if x0 <= lon <= x1 and y0 <= lat <= y1 and _in_ring(lon, lat, outer):
            if not any(_in_ring(lon, lat, h) for h in holes):
                return True
    return False


def area_fraction(points, samples: int = 500) -> float:
    """Share of the track (evenly sampled) that lies inside the area."""
    if not points:
        return 0.0
    step = max(1, math.ceil(len(points) / samples))
    picked = points[::step]
    return sum(in_area(lat, lon) for lat, lon in picked) / len(picked)
