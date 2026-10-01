#!/usr/bin/env python3
"""Generate a small local country outline SVG from public-domain Natural Earth GeoJSON.

Input: datasets/geo-countries countries.geojson (Natural Earth Admin 0, 1:110m).
The generated asset contains only country boundaries/names/codes, never peer data.
"""
import html
import json
import sys
from pathlib import Path


def distance(point, a, b):
    x, y = point
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    if dx == dy == 0:
        return (x - ax) ** 2 + (y - ay) ** 2
    t = max(0, min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
    return (x - ax - t * dx) ** 2 + (y - ay - t * dy) ** 2


def simplify(points, tolerance_squared=0.16):
    if len(points) < 4:
        return points
    stack = [(0, len(points) - 1)]
    keep = {0, len(points) - 1}
    while stack:
        start, end = stack.pop()
        middle = max(range(start + 1, end), key=lambda i: distance(points[i], points[start], points[end]), default=None)
        if middle is not None and distance(points[middle], points[start], points[end]) > tolerance_squared:
            keep.add(middle)
            stack.extend(((start, middle), (middle, end)))
    return [points[i] for i in sorted(keep)]


def rings(geometry):
    if geometry["type"] == "Polygon":
        return geometry["coordinates"]
    if geometry["type"] == "MultiPolygon":
        return [ring for polygon in geometry["coordinates"] for ring in polygon]
    return []


def path_for(geometry):
    result = []
    for ring in rings(geometry):
        if len(ring) < 4:
            continue
        # Split at the antimeridian; never draw a false line across the map.
        segments = [[]]
        for lon, lat in ring:
            if segments[-1] and abs(lon - segments[-1][-1][0]) > 180:
                segments.append([])
            segments[-1].append((lon, lat))
        for segment in segments:
            points = simplify(segment)
            if len(points) < 3:
                continue
            projected = [(round((lon + 180) * 960 / 360, 1), round((90 - lat) * 480 / 180, 1))
                         for lon, lat in points]
            result.append("M" + "L".join(f"{x:g},{y:g}" for x, y in projected) + "Z")
    return "".join(result)


def main(source, destination):
    data = json.loads(Path(source).read_text())
    lines = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 480" role="img" '
             'aria-label="Country-level approximate network geography">',
             '<g fill="#223044" stroke="#3a4b5d" stroke-width="0.45">']
    for feature in sorted(data["features"], key=lambda f: f["properties"].get("name", "")):
        properties = feature["properties"]
        code = properties.get("ISO3166-1-Alpha-2", "")
        if len(code) != 2 or not code.isalpha():
            continue
        name = html.escape(properties.get("name", code), quote=True)
        path = path_for(feature["geometry"])
        if path:
            lines.append(f'<path data-country="{code}" data-name="{name}" d="{path}"><title>{name}</title></path>')
    lines.extend(['</g>', '</svg>'])
    Path(destination).write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: build-dashboard-map.py source-geojson output-svg")
    main(*sys.argv[1:])
