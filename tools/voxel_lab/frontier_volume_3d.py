from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Callable, Iterable, Literal

from closed_volumes import quad_halfspace_geometry, quad_halfspace_solid
from frontier_scan_3d import (
    canonical_nested_frontier_face,
    center,
    edge_vertices,
)
from planar_faces import Voxel, nested_bresenham_path
from worker_surfaces import NEIGHBORS, add_voxel, six_neighbor_boundary

BridgeOrder = Literal[
    "parallel",
    "secondary_then_tertiary",
    "tertiary_then_secondary",
    "both_orders",
]


def subtract_voxel(first: Voxel, second: Voxel) -> Voxel:
    return tuple(first[axis] - second[axis] for axis in range(3))  # type: ignore[return-value]


def axis_offset(axis: int, sign: int) -> Voxel:
    coordinates = [0, 0, 0]
    coordinates[axis] = sign
    return coordinates[0], coordinates[1], coordinates[2]


def exposed_frontier(section: Iterable[Voxel], offset: Voxel) -> frozenset[Voxel]:
    materialized = frozenset(section)
    return frozenset(
        voxel
        for voxel in materialized
        if add_voxel(voxel, offset) not in materialized
    )


def ranked_axes(edge: Voxel) -> tuple[int, int, int]:
    return tuple(sorted(range(3), key=lambda axis: (-abs(edge[axis]), axis)))  # type: ignore[return-value]


def parallelepiped_edges(
    origin: Voxel,
    edge_a: Voxel,
    edge_b: Voxel,
    edge_c: Voxel,
) -> tuple[tuple[Voxel, ...], ...]:
    base = edge_vertices(origin, edge_a, edge_b)
    top = tuple(add_voxel(vertex, edge_c) for vertex in base)
    horizontal = tuple(
        nested_bresenham_path(vertices[index], vertices[(index + 1) % 4])
        for vertices in (base, top)
        for index in range(4)
    )
    vertical = tuple(
        nested_bresenham_path(base[index], top[index])
        for index in range(4)
    )
    return horizontal + vertical


@dataclass(frozen=True)
class FrontierVolume:
    solid: frozenset[Voxel]
    hollow: frozenset[Voxel]
    logical_sections: tuple[frozenset[Voxel], ...]
    bridge_layers: tuple[frozenset[Voxel], ...]
    owned_edges: tuple[tuple[Voxel, ...], ...]
    halfspace_solid: frozenset[Voxel]
    base_output_axis_bridges: frozenset[Voxel]

    @property
    def missing_owned_edge_voxels(self) -> frozenset[Voxel]:
        owned = frozenset(voxel for edge in self.owned_edges for voxel in edge)
        return frozenset(owned - self.hollow)

    @property
    def expansion_over_halfspace(self) -> frozenset[Voxel]:
        return frozenset(self.solid - self.halfspace_solid)

    @property
    def missing_from_halfspace(self) -> frozenset[Voxel]:
        return frozenset(self.halfspace_solid - self.solid)


def continuous_inside(
    origin: Voxel,
    edge_a: Voxel,
    edge_b: Voxel,
    edge_c: Voxel,
) -> Callable[[Voxel], bool]:
    base = tuple(center(vertex) for vertex in edge_vertices(origin, edge_a, edge_b))
    _, inside = quad_halfspace_geometry(
        base,
        tuple(float(value) for value in edge_c),
    )
    return inside


def bridge_axes_for_step(
    step: Voxel,
    ranked: tuple[int, int, int],
    order: BridgeOrder,
) -> tuple[int, ...]:
    changed = {axis for axis in range(3) if step[axis] != 0}
    optional = [axis for axis in ranked[1:] if axis in changed]
    if order == "tertiary_then_secondary":
        optional.reverse()
    return tuple(optional)


def bridge_layer(
    old_section: frozenset[Voxel],
    step: Voxel,
    edge_c: Voxel,
    ranked: tuple[int, int, int],
    inside: Callable[[Voxel], bool],
    order: BridgeOrder,
) -> frozenset[Voxel]:
    if order == "both_orders":
        return bridge_layer(
            old_section,
            step,
            edge_c,
            ranked,
            inside,
            "secondary_then_tertiary",
        ) | bridge_layer(
            old_section,
            step,
            edge_c,
            ranked,
            inside,
            "tertiary_then_secondary",
        )
    axes = bridge_axes_for_step(step, ranked, order)
    emitted: set[Voxel] = set()
    working = old_section
    for axis in axes:
        offset = axis_offset(axis, 1 if edge_c[axis] >= 0 else -1)
        source = old_section if order == "parallel" else working
        candidates = {
            add_voxel(voxel, offset)
            for voxel in exposed_frontier(source, offset)
        }
        accepted = {voxel for voxel in candidates if inside(voxel)}
        emitted.update(accepted)
        if order != "parallel":
            working = frozenset(set(working) | accepted)
    return frozenset(emitted)


def nested_frontier_volume(
    origin: Voxel,
    edge_a: Voxel,
    edge_b: Voxel,
    edge_c: Voxel,
    order: BridgeOrder,
    bridge_face_output_axis: bool = False,
) -> FrontierVolume:
    inside = continuous_inside(origin, edge_a, edge_b, edge_c)
    base = canonical_nested_frontier_face(
        origin,
        edge_a,
        edge_b,
        clip_bridges_to_polygon=True,
        clip_logical_slices_to_polygon=True,
        frontier_uses_unclipped_slice=True,
        bridge_output_axis=bridge_face_output_axis,
        accept_output_axis_bridge=inside if bridge_face_output_axis else None,
    )
    base_section = base.voxels
    sweep = nested_bresenham_path(origin, add_voxel(origin, edge_c))
    ranked = ranked_axes(edge_c)
    logical_sections: list[frozenset[Voxel]] = []
    bridges: list[frozenset[Voxel]] = []
    solid: set[Voxel] = set()
    previous_section: frozenset[Voxel] | None = None
    previous_sweep: Voxel | None = None
    for sweep_point in sweep:
        offset = subtract_voxel(sweep_point, origin)
        logical = frozenset(add_voxel(voxel, offset) for voxel in base_section)
        bridge = frozenset()
        if previous_section is not None and previous_sweep is not None:
            step = subtract_voxel(sweep_point, previous_sweep)
            if sum(component != 0 for component in step) > 1:
                bridge = bridge_layer(
                    previous_section,
                    step,
                    edge_c,
                    ranked,
                    inside,
                    order,
                )
        logical_sections.append(logical)
        bridges.append(bridge)
        solid.update(logical)
        solid.update(bridge)
        previous_section = logical
        previous_sweep = sweep_point

    halfspace = quad_halfspace_solid(
        tuple(center(vertex) for vertex in edge_vertices(origin, edge_a, edge_b)),
        tuple(float(value) for value in edge_c),
    )
    boundary = six_neighbor_boundary(solid)
    return FrontierVolume(
        solid=frozenset(solid),
        hollow=frozenset(boundary),
        logical_sections=tuple(logical_sections),
        bridge_layers=tuple(bridges),
        owned_edges=parallelepiped_edges(origin, edge_a, edge_b, edge_c),
        halfspace_solid=frozenset(halfspace),
        base_output_axis_bridges=frozenset(
            voxel
            for layer in base.output_axis_bridge_layers
            for voxel in layer
        ),
    )


def enclosed_air(solid: Iterable[Voxel]) -> frozenset[Voxel]:
    materialized = frozenset(solid)
    if not materialized:
        return frozenset()
    ranges = tuple(
        range(
            min(voxel[axis] for voxel in materialized) - 1,
            max(voxel[axis] for voxel in materialized) + 2,
        )
        for axis in range(3)
    )
    limits = tuple((axis_range.start, axis_range.stop) for axis_range in ranges)
    start = ranges[0].start, ranges[1].start, ranges[2].start
    exterior = {start}
    queue = deque((start,))
    while queue:
        voxel = queue.popleft()
        for offset in NEIGHBORS:
            candidate = add_voxel(voxel, offset)
            if candidate in exterior or candidate in materialized:
                continue
            if any(
                candidate[axis] < limits[axis][0]
                or candidate[axis] >= limits[axis][1]
                for axis in range(3)
            ):
                continue
            exterior.add(candidate)
            queue.append(candidate)
    return frozenset(
        (x, y, z)
        for x in ranges[0]
        for y in ranges[1]
        for z in ranges[2]
        if (x, y, z) not in materialized and (x, y, z) not in exterior
    )
