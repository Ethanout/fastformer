from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable

from closed_volumes import average, quad_prism_faces
from frontier_scan_3d import center
from frontier_volume_3d import FrontierVolume, enclosed_air, nested_frontier_volume
from planar_faces import EPSILON, Vec3, Voxel, dot, face_normal, normalize, subtract
from worker_surfaces import NEIGHBORS, add_voxel, six_neighbor_boundary


def continuous_planes(volume: FrontierVolume) -> tuple[tuple[Vec3, Vec3, float], ...]:
    points = tuple(center(voxel) for edge in volume.owned_edges for voxel in (edge[0], edge[-1]))
    unique_points = tuple(dict.fromkeys(points))
    # Recover the eight corners from the twelve owned paths.  A parallelepiped
    # has exactly eight unique endpoints even when some raster paths overlap.
    if len(unique_points) != 8:
        raise ValueError(f"expected eight owned corners, got {len(unique_points)}")
    shape_center = average(unique_points)

    # The first four paths are the base cycle and the next four the top cycle.
    base = tuple(center(volume.owned_edges[index][0]) for index in range(4))
    top_start = center(volume.owned_edges[4][0])
    extrusion = subtract(top_start, base[0])
    planes: list[tuple[Vec3, Vec3, float]] = []
    for face in quad_prism_faces(base, extrusion):
        normal = normalize(face_normal(face))
        origin = face[0]
        center_distance = dot(normal, subtract(shape_center, origin))
        if abs(center_distance) <= EPSILON:
            raise ValueError("degenerate continuous face")
        planes.append((origin, normal, math.copysign(1.0, center_distance)))
    return tuple(planes)


def halfspace_margin(voxel: Voxel, planes: tuple[tuple[Vec3, Vec3, float], ...]) -> float:
    point = center(voxel)
    return min(
        sign * dot(normal, subtract(point, origin))
        for origin, normal, sign in planes
    )


def ray_to_air(
    start: Voxel,
    direction: Voxel,
    solid: set[Voxel],
    outline: frozenset[Voxel],
) -> tuple[Voxel, ...] | None:
    path: list[Voxel] = []
    current = start
    while current in solid:
        if current in outline:
            return None
        path.append(current)
        current = add_voxel(current, direction)
    return tuple(path)


def enclosed_components(solid: set[Voxel]) -> tuple[frozenset[Voxel], ...]:
    remaining = set(enclosed_air(solid))
    components: list[frozenset[Voxel]] = []
    while remaining:
        seed = remaining.pop()
        component = {seed}
        stack = [seed]
        while stack:
            voxel = stack.pop()
            for direction in NEIGHBORS:
                neighbor = add_voxel(voxel, direction)
                if neighbor in remaining:
                    remaining.remove(neighbor)
                    component.add(neighbor)
                    stack.append(neighbor)
        components.append(frozenset(component))
    return tuple(components)


@dataclass(frozen=True)
class OwnershipCarveResult:
    solid: frozenset[Voxel]
    boundary: frozenset[Voxel]
    outline: frozenset[Voxel]
    forbidden_air: frozenset[Voxel]
    carve_paths: tuple[tuple[Voxel, ...], ...]
    initially_filled_air: frozenset[Voxel]
    finally_filled_air: frozenset[Voxel]
    remaining_enclosed_air: frozenset[Voxel]

    @property
    def missing_outline(self) -> frozenset[Voxel]:
        return frozenset(self.outline - self.boundary)

    @property
    def deleted_voxels(self) -> int:
        return len(self.forbidden_air)

    @property
    def maximum_carve_depth(self) -> int:
        return max((len(path) for path in self.carve_paths), default=0)


def ownership_carve(volume: FrontierVolume) -> OwnershipCarveResult:
    outline = frozenset(voxel for edge in volume.owned_edges for voxel in edge)
    solid = set(volume.solid)
    initial_holes = enclosed_air(solid)
    solid.update(initial_holes)
    planes = continuous_planes(volume)
    forbidden: set[Voxel] = set()
    paths: list[tuple[Voxel, ...]] = []

    # Canonical voxel order makes overlapping tunnels deterministic.  Boundary
    # is recomputed after every carve because one tunnel can expose several
    # later owned voxels at once.
    for owned in sorted(outline):
        if owned in six_neighbor_boundary(solid):
            continue
        candidates: list[tuple[float, int, Voxel, tuple[Voxel, ...]]] = []
        for direction in NEIGHBORS:
            neighbor = add_voxel(owned, direction)
            if neighbor in outline:
                continue
            path = ray_to_air(neighbor, direction, solid, outline)
            if path is None or not path:
                continue
            candidates.append((
                halfspace_margin(neighbor, planes),
                len(path),
                direction,
                path,
            ))
        if not candidates:
            continue
        _, _, _, chosen = min(candidates, key=lambda item: (item[0], item[1], item[2]))
        solid.difference_update(chosen)
        forbidden.update(chosen)
        paths.append(chosen)

    final_fill: set[Voxel] = set()
    for component in enclosed_components(solid):
        if component.isdisjoint(forbidden):
            final_fill.update(component)
    solid.update(final_fill)
    boundary = six_neighbor_boundary(solid)
    return OwnershipCarveResult(
        solid=frozenset(solid),
        boundary=frozenset(boundary),
        outline=outline,
        forbidden_air=frozenset(forbidden),
        carve_paths=tuple(paths),
        initially_filled_air=frozenset(initial_holes),
        finally_filled_air=frozenset(final_fill),
        remaining_enclosed_air=enclosed_air(solid),
    )


def secondary_volume(
    origin: Voxel,
    edge_a: Voxel,
    edge_b: Voxel,
    edge_c: Voxel,
) -> FrontierVolume:
    return nested_frontier_volume(
        origin,
        edge_a,
        edge_b,
        edge_c,
        "secondary_then_tertiary",
    )
