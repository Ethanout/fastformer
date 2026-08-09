"""Canonical parallelepiped volume built from complete integer scan intervals."""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from itertools import product
from math import ceil, floor
from pathlib import Path

from closed_volumes import NEIGHBORS, add_voxel, center_of, quad_prism_faces
from planar_faces import EPSILON, Vec3, Voxel, add, dot, face_normal, normalize, subtract


@dataclass(frozen=True)
class Plane:
    origin: Vec3
    inward: Vec3

    def slack(self, point: Vec3) -> float:
        return dot(self.inward, subtract(point, self.origin))


@dataclass(frozen=True)
class CanonicalVolume:
    solid: frozenset[Voxel]
    faces: tuple[frozenset[Voxel], ...]
    scan_axis: int
    intervals: tuple[tuple[Voxel, ...], ...]

    @property
    def boundary(self) -> frozenset[Voxel]:
        return frozenset().union(*self.faces)

    def progressive(self):
        """Interleave equal inward depths; a prefix never masquerades as one cap."""
        depth = 0
        emitted: set[Voxel] = set()
        while True:
            active = False
            for line in self.intervals:
                for index in (depth, len(line) - 1 - depth):
                    if 0 <= index < len(line):
                        active = True
                        voxel = line[index]
                        if voxel not in emitted:
                            emitted.add(voxel)
                            yield voxel
            if not active:
                return
            depth += 1


def planes_for(base: tuple[Vec3, ...], extrusion: Vec3) -> tuple[Plane, ...]:
    vertices = base + tuple(add(point, extrusion) for point in base)
    center = tuple(sum(point[axis] for point in vertices) / len(vertices) for axis in range(3))
    result = []
    for face in quad_prism_faces(base, extrusion):
        normal = normalize(face_normal(face))
        if dot(normal, subtract(center, face[0])) < 0:
            normal = tuple(-value for value in normal)
        result.append(Plane(face[0], normal))
    return tuple(result)


def bounds_for(base: tuple[Vec3, ...], extrusion: Vec3) -> tuple[range, range, range]:
    vertices = base + tuple(add(point, extrusion) for point in base)
    return tuple(
        range(floor(min(point[a] for point in vertices)), floor(max(point[a] for point in vertices)) + 1)
        for a in range(3)
    )  # type: ignore[return-value]


def choose_scan_axis(bounds: tuple[range, range, range]) -> int:
    """Longest world-axis extent minimizes the number of independent intervals."""
    return max(range(3), key=lambda axis: (len(bounds[axis]), -axis))


def interval_for(
    fixed: tuple[int, int], axes: tuple[int, int], scan_axis: int,
    scan_range: range, planes: tuple[Plane, ...],
) -> tuple[Voxel, ...]:
    """Solve all halfspaces on one integer-center line, then emit its exact interval."""
    coordinates = [0.0, 0.0, 0.0]
    coordinates[axes[0]] = fixed[0] + .5
    coordinates[axes[1]] = fixed[1] + .5
    low, high = float(scan_range.start), float(scan_range.stop - 1)
    for plane in planes:
        coordinates[scan_axis] = .5
        constant = plane.slack(tuple(coordinates))
        coefficient = plane.inward[scan_axis]
        if abs(coefficient) <= EPSILON:
            if constant < -EPSILON:
                return ()
            continue
        crossing = -constant / coefficient
        if coefficient > 0:
            low = max(low, ceil(crossing - EPSILON))
        else:
            high = min(high, floor(crossing + EPSILON))
        if low > high:
            return ()
    result = []
    for value in range(int(low), int(high) + 1):
        voxel = [0, 0, 0]
        voxel[axes[0]], voxel[axes[1]], voxel[scan_axis] = fixed[0], fixed[1], value
        result.append(tuple(voxel))
    return tuple(result)


def rasterize(base: tuple[Vec3, ...], extrusion: Vec3) -> CanonicalVolume:
    planes = planes_for(base, extrusion)
    bounds = bounds_for(base, extrusion)
    scan_axis = choose_scan_axis(bounds)
    axes = tuple(axis for axis in range(3) if axis != scan_axis)
    intervals = tuple(
        line for fixed in product(bounds[axes[0]], bounds[axes[1]])
        if (line := interval_for(fixed, axes, scan_axis, bounds[scan_axis], planes))
    )
    solid = frozenset(voxel for line in intervals for voxel in line)

    # A boundary voxel is owned by every closest mathematical face.  Ownership
    # is descriptive: it never appends an outward worker/riser voxel.
    owners = [set() for _ in planes]
    for voxel in solid:
        if all(add_voxel(voxel, step) in solid for step in NEIGHBORS):
            continue
        slacks = tuple(plane.slack(center_of(voxel)) for plane in planes)
        minimum = min(slacks)
        for index, slack in enumerate(slacks):
            if slack <= minimum + EPSILON:
                owners[index].add(voxel)
    return CanonicalVolume(solid, tuple(frozenset(face) for face in owners), scan_axis, intervals)


def six_boundary(solid: frozenset[Voxel]) -> frozenset[Voxel]:
    return frozenset(voxel for voxel in solid if any(add_voxel(voxel, step) not in solid for step in NEIGHBORS))


def axis_intervals_are_contiguous(volume: CanonicalVolume) -> bool:
    return all(
        all(line[index][volume.scan_axis] + 1 == line[index + 1][volume.scan_axis]
            for index in range(len(line) - 1))
        for line in volume.intervals
    )


def is_six_connected(points: frozenset[Voxel]) -> bool:
    if not points:
        return True
    seed = next(iter(points)); seen, queue = {seed}, deque((seed,))
    while queue:
        voxel = queue.popleft()
        for step in NEIGHBORS:
            neighbor = add_voxel(voxel, step)
            if neighbor in points and neighbor not in seen:
                seen.add(neighbor); queue.append(neighbor)
    return seen == points


def maximum_boundary_deviation(volume: CanonicalVolume, planes: tuple[Plane, ...]) -> float:
    """Distance along the owning unit normal; a real boundary is at most one voxel away."""
    return max(
        (min(plane.slack(center_of(voxel)) for plane in planes) for voxel in volume.boundary),
        default=0.0,
    )


def has_enclosed_air(solid: frozenset[Voxel]) -> bool:
    if not solid:
        return False
    ranges = tuple(range(min(v[a] for v in solid) - 1, max(v[a] for v in solid) + 2) for a in range(3))
    start = tuple(axis.start for axis in ranges)
    exterior, queue = {start}, deque((start,))
    while queue:
        voxel = queue.popleft()
        for step in NEIGHBORS:
            candidate = add_voxel(voxel, step)
            if candidate in solid or candidate in exterior:
                continue
            if all(candidate[a] in ranges[a] for a in range(3)):
                exterior.add(candidate); queue.append(candidate)
    return any(
        voxel not in solid and voxel not in exterior
        for voxel in product(*ranges)
    )


def render(volume: CanonicalVolume, output=None):
    import matplotlib.pyplot as plt
    import numpy as np

    data = np.array(sorted(volume.solid)); shifted = data - data.min(axis=0)
    voxels = np.zeros(tuple(shifted.max(axis=0) + 1), dtype=bool)
    colors = np.empty(voxels.shape, dtype=object)
    palette = ("#62b6ff", "#ff8f70", "#7bd389", "#ffd166", "#b896ff", "#54d6c3")
    for world, local in zip(data, shifted):
        key = tuple(local); voxels[key] = True
        colors[key] = next((palette[i] for i, face in enumerate(volume.faces) if tuple(world) in face), "#d8e8f5")
    figure = plt.figure(figsize=(8, 7)); axis = figure.add_subplot(projection="3d")
    axis.voxels(voxels, facecolors=colors, edgecolor="#17324d", linewidth=.18)
    axis.set_title("canonical interval solid; color = owning face")
    figure.tight_layout(); output = Path(output or Path(__file__).with_suffix(".png")); figure.savefig(output, dpi=180)
    return output


if __name__ == "__main__":
    base = ((.5, .5, .5), (9.5, 3.5, 6.5), (5.5, 11.5, 10.5), (-3.5, 8.5, 4.5))
    volume = rasterize(base, (7., -5., 9.))
    progressive = tuple(volume.progressive())
    print(f"solid={len(volume.solid)} boundary={len(volume.boundary)} axis={volume.scan_axis}")
    print(f"intervals={axis_intervals_are_contiguous(volume)} owned={volume.boundary == six_boundary(volume.solid)} holes={has_enclosed_air(volume.solid)} six={is_six_connected(volume.solid)}")
    print(f"max_boundary_deviation={maximum_boundary_deviation(volume, planes_for(base, (7., -5., 9.))):.6f}")
    print(f"progressive_unique={len(progressive) == len(set(progressive))} complete={set(progressive) == volume.solid}")
    print(render(volume))
