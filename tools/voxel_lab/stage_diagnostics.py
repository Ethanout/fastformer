from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Iterable

from frontier_volume_3d import FrontierVolume, enclosed_air
from ownership_carve import OwnershipCarveResult, ownership_carve
from planar_faces import Voxel
from worker_surfaces import NEIGHBORS, add_voxel, six_neighbor_boundary


def connected_components(voxels: Iterable[Voxel]) -> tuple[frozenset[Voxel], ...]:
    remaining = set(voxels)
    result: list[frozenset[Voxel]] = []
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
        result.append(frozenset(component))
    return tuple(result)


def exterior_air(solid: set[Voxel], reference: set[Voxel]) -> frozenset[Voxel]:
    occupied_extent = solid | reference
    if not occupied_extent:
        return frozenset()
    ranges = tuple(
        range(
            min(voxel[axis] for voxel in occupied_extent) - 1,
            max(voxel[axis] for voxel in occupied_extent) + 2,
        )
        for axis in range(3)
    )
    limits = tuple((axis_range.start, axis_range.stop) for axis_range in ranges)
    start = ranges[0].start, ranges[1].start, ranges[2].start
    exterior = {start}
    queue = deque((start,))
    while queue:
        voxel = queue.popleft()
        for direction in NEIGHBORS:
            candidate = add_voxel(voxel, direction)
            if candidate in exterior or candidate in solid:
                continue
            if any(
                candidate[axis] < limits[axis][0]
                or candidate[axis] >= limits[axis][1]
                for axis in range(3)
            ):
                continue
            exterior.add(candidate)
            queue.append(candidate)
    return frozenset(exterior)


def maximum_penetration_depth(open_voids: frozenset[Voxel], halfspace: set[Voxel]) -> int:
    if not open_voids:
        return 0
    halfspace_boundary = six_neighbor_boundary(halfspace)
    entries = set(open_voids & halfspace_boundary)
    if not entries:
        return 0
    distance = {voxel: 1 for voxel in entries}
    queue = deque(entries)
    while queue:
        voxel = queue.popleft()
        for direction in NEIGHBORS:
            neighbor = add_voxel(voxel, direction)
            if neighbor in open_voids and neighbor not in distance:
                distance[neighbor] = distance[voxel] + 1
                queue.append(neighbor)
    return max(distance.values(), default=0)


@dataclass(frozen=True)
class SurfaceVoidDiagnostic:
    enclosed_air: frozenset[Voxel]
    open_halfspace_voids: frozenset[Voxel]
    open_components: tuple[frozenset[Voxel], ...]
    entry_patches: tuple[frozenset[Voxel], ...]
    maximum_penetration_depth: int

    @property
    def through_component_count(self) -> int:
        entry_by_component = []
        for component in self.open_components:
            entry_by_component.append(sum(bool(component & patch) for patch in self.entry_patches))
        return sum(count >= 2 for count in entry_by_component)


def diagnose_surface_voids(solid: Iterable[Voxel], halfspace: Iterable[Voxel]) -> SurfaceVoidDiagnostic:
    materialized = set(solid)
    reference = set(halfspace)
    exterior = exterior_air(materialized, reference)
    open_voids = frozenset((reference - materialized) & exterior)
    halfspace_boundary = six_neighbor_boundary(reference)
    entries = open_voids & halfspace_boundary
    return SurfaceVoidDiagnostic(
        enclosed_air=enclosed_air(materialized),
        open_halfspace_voids=open_voids,
        open_components=connected_components(open_voids),
        entry_patches=connected_components(entries),
        maximum_penetration_depth=maximum_penetration_depth(open_voids, reference),
    )


@dataclass(frozen=True)
class ThreeStageDiagnostic:
    pre_fill: SurfaceVoidDiagnostic
    post_fill: SurfaceVoidDiagnostic
    post_expose: SurfaceVoidDiagnostic
    expose_result: OwnershipCarveResult
    expose_new_open_voids: frozenset[Voxel]
    expose_deleted_halfspace_voxels: frozenset[Voxel]

    @property
    def expose_manufactured_opening(self) -> bool:
        return bool(self.expose_new_open_voids)


def diagnose_three_stages(volume: FrontierVolume) -> ThreeStageDiagnostic:
    halfspace = set(volume.halfspace_solid)
    pre_solid = set(volume.solid)
    filled_solid = pre_solid | set(enclosed_air(pre_solid))
    exposed = ownership_carve(volume)
    pre = diagnose_surface_voids(pre_solid, halfspace)
    post_fill = diagnose_surface_voids(filled_solid, halfspace)
    post_expose = diagnose_surface_voids(exposed.solid, halfspace)
    return ThreeStageDiagnostic(
        pre_fill=pre,
        post_fill=post_fill,
        post_expose=post_expose,
        expose_result=exposed,
        expose_new_open_voids=frozenset(
            post_expose.open_halfspace_voids - post_fill.open_halfspace_voids
        ),
        expose_deleted_halfspace_voxels=frozenset(
            (filled_solid - set(exposed.solid)) & halfspace
        ),
    )
