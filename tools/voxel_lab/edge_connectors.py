"""Experiment: close diagonal jumps on the 12 prism edges only."""
from __future__ import annotations

from itertools import permutations
from math import dist

from canonical_volume_lab import axis_intervals_are_contiguous, is_six_connected, rasterize
from closed_volumes import NEIGHBORS, add_voxel, center_of
from planar_faces import containing, nested_bresenham_path, subtract


def prism_edges(base, extrusion):
    top = tuple(tuple(p[i] + extrusion[i] for i in range(3)) for p in base)
    edges = []
    for ring in (base, top):
        edges.extend((ring[i], ring[(i + 1) % 4]) for i in range(4))
    edges.extend((base[i], top[i]) for i in range(4))
    return tuple(edges)


def _point_segment_distance(point, start, end):
    d = subtract(end, start); length2 = sum(value * value for value in d)
    if length2 == 0: return dist(point, start)
    t = max(0.0, min(1.0, sum((point[i] - start[i]) * d[i] for i in range(3)) / length2))
    projection = tuple(start[i] + t * d[i] for i in range(3))
    return dist(point, projection)


def _shortest_paths(start, end):
    delta = tuple(end[i] - start[i] for i in range(3))
    axes = tuple(i for i, value in enumerate(delta) if value)
    if any(abs(delta[i]) > 1 for i in range(3)): raise ValueError((start, end))
    for order in permutations(axes):
        path = [start]; current = list(start)
        for axis in order:
            current[axis] += delta[axis]; path.append(tuple(current))
        yield tuple(path)


def connect_edges(solid, base, extrusion):
    """Return connected copy, added voxels, transition count, outside count."""
    result, added = set(solid), set(); transitions = outside = 0
    for edge_start, edge_end in prism_edges(base, extrusion):
        edge = nested_bresenham_path(containing(edge_start), containing(edge_end))
        for start, end in zip(edge, edge[1:]):
            if sum(abs(end[i] - start[i]) for i in range(3)) <= 1: continue
            transitions += 1
            chosen = min(
                _shortest_paths(start, end),
                key=lambda path: (
                    sum(voxel not in solid for voxel in path[1:-1]),
                    sum(_point_segment_distance(center_of(voxel), edge_start, edge_end) for voxel in path[1:-1]),
                    tuple(path),
                ),
            )
            for voxel in chosen[1:-1]:
                if voxel not in result:
                    result.add(voxel); added.add(voxel); outside += voxel not in solid
    return result, added, transitions, outside


def edge_report(base, extrusion):
    volume = rasterize(base, extrusion)
    connected, added, transitions, outside = connect_edges(volume.solid, base, extrusion)
    return {
        "solid": len(volume.solid), "added": len(added), "transitions": transitions,
        "outside": outside, "six_before": is_six_connected(volume.solid),
        "six_after": is_six_connected(frozenset(connected)),
        "intervals_before": axis_intervals_are_contiguous(volume),
        "intervals_after": all(_axis_contiguous(connected, axis) for axis in range(3)),
    }


def _axis_contiguous(solid, axis):
    others = tuple(a for a in range(3) if a != axis); lines = {}
    for voxel in solid: lines.setdefault((voxel[others[0]], voxel[others[1]]), []).append(voxel[axis])
    return all(max(values) - min(values) + 1 == len(values) for values in lines.values())


if __name__ == "__main__":
    base = ((.5, .5, .5), (9.5, 3.5, 6.5), (5.5, 11.5, 10.5), (-3.5, 8.5, 4.5))
    print(edge_report(base, (7.0, -5.0, 9.0)))
