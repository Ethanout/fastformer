from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Callable, Iterable, Sequence

from planar_faces import (
    EPSILON,
    Projection,
    Vec3,
    Voxel,
    contains,
    cross,
    length_squared,
    nested_bresenham_path,
    projected_cells,
)
from worker_surfaces import add_voxel

Pixel = tuple[int, int]


def negate(voxel: Voxel) -> Voxel:
    return -voxel[0], -voxel[1], -voxel[2]


def center(voxel: Voxel) -> Vec3:
    return voxel[0] + 0.5, voxel[1] + 0.5, voxel[2] + 0.5


def edge_vertices(origin: Voxel, edge_a: Voxel, edge_b: Voxel) -> tuple[Voxel, ...]:
    return (
        origin,
        add_voxel(origin, edge_a),
        add_voxel(origin, add_voxel(edge_a, edge_b)),
        add_voxel(origin, edge_b),
    )


def plane_vertices(origin: Voxel, edge_a: Voxel, edge_b: Voxel) -> tuple[Vec3, ...]:
    return tuple(center(voxel) for voxel in edge_vertices(origin, edge_a, edge_b))


def owned_paths(origin: Voxel, edge_a: Voxel, edge_b: Voxel) -> tuple[tuple[Voxel, ...], ...]:
    vertices = edge_vertices(origin, edge_a, edge_b)
    return tuple(
        nested_bresenham_path(vertices[index], vertices[(index + 1) % 4])
        for index in range(4)
    )


@dataclass(frozen=True)
class ProjectedSweep:
    major_index: int
    minor_index: int
    major_sign: int
    minor_sign: int

    @staticmethod
    def from_delta(delta: Pixel) -> "ProjectedSweep":
        distance = abs(delta[0]), abs(delta[1])
        # Match the nested line's deterministic lower-axis tie preference in
        # the projection's ordered input axes.
        major = 0 if distance[0] >= distance[1] else 1
        minor = 1 - major
        return ProjectedSweep(
            major_index=major,
            minor_index=minor,
            major_sign=1 if delta[major] >= 0 else -1,
            minor_sign=1 if delta[minor] >= 0 else -1,
        )

    @property
    def minor_offset(self) -> Pixel:
        coordinates = [0, 0]
        coordinates[self.minor_index] = self.minor_sign
        return coordinates[0], coordinates[1]

    def is_diagonal_event(self, previous: Pixel, following: Pixel) -> bool:
        delta = following[0] - previous[0], following[1] - previous[1]
        return delta[self.major_index] != 0 and delta[self.minor_index] != 0


@dataclass(frozen=True)
class NestedFrontierFace:
    voxels: frozenset[Voxel]
    logical_slices: tuple[frozenset[Voxel], ...]
    bridge_layers: tuple[frozenset[Voxel], ...]
    output_axis_bridge_layers: tuple[frozenset[Voxel], ...]
    owned_edges: tuple[tuple[Voxel, ...], ...]
    projection: Projection

    @property
    def projected_cells(self) -> frozenset[Pixel]:
        return frozenset(self.projection.project_voxel(voxel) for voxel in self.voxels)

    @property
    def maximum_projection_column_thickness(self) -> int:
        counts = Counter(self.projection.project_voxel(voxel) for voxel in self.voxels)
        return max(counts.values(), default=0)


def positive_frontier(points: Iterable[Pixel], offset: Pixel) -> frozenset[Pixel]:
    materialized = frozenset(points)
    return frozenset(
        point
        for point in materialized
        if (point[0] + offset[0], point[1] + offset[1]) not in materialized
    )


def restore_bridge(
    pixel: Pixel,
    projection: Projection,
    plane_origin: Vec3,
    normal: Vec3,
) -> Voxel:
    return projection.restore(pixel[0], pixel[1], plane_origin, normal)


def output_axis_bridge_layer(
    previous_slice: Iterable[Voxel],
    current_slice: Iterable[Voxel],
    step: Voxel,
    projection: Projection,
    accept: Callable[[Voxel], bool] | None = None,
) -> frozenset[Voxel]:
    """Bridge a dropped-axis diagonal without adding a projected cell.

    A logical Pi translation can advance the projection output (dropped) axis
    and one or both in-plane axes in the same Bresenham tick.  The old and new
    Pi then touch only diagonally in 3-D even though their canonical 2-D cells
    are adjacent.  For every exposed old Pi point, retain its dropped-axis
    coordinate and apply only the in-plane part of the step.  The candidate is
    accepted only when its projected cell is already owned by the new logical
    Pi, so this bridge can thicken an existing projection column but cannot
    expand the canonical 2-D boundary.
    """
    if step[projection.output_axis] == 0:
        return frozenset()
    projected_offset_coordinates = [0, 0, 0]
    for axis in projection.input_axes:
        projected_offset_coordinates[axis] = step[axis]
    projected_offset = (
        projected_offset_coordinates[projection.input_axes[0]],
        projected_offset_coordinates[projection.input_axes[1]],
    )
    if projected_offset == (0, 0):
        return frozenset()

    previous = frozenset(previous_slice)
    current = frozenset(current_slice)
    previous_pixels = frozenset(projection.project_voxel(voxel) for voxel in previous)
    current_pixels = frozenset(projection.project_voxel(voxel) for voxel in current)
    frontier = positive_frontier(previous_pixels, projected_offset)
    offset: Voxel = (
        projected_offset_coordinates[0],
        projected_offset_coordinates[1],
        projected_offset_coordinates[2],
    )
    candidates = {
        add_voxel(voxel, offset)
        for voxel in previous
        if projection.project_voxel(voxel) in frontier
    }
    return frozenset(
        candidate
        for candidate in candidates
        if candidate not in previous
        and candidate not in current
        and projection.project_voxel(candidate) in current_pixels
        and (accept is None or accept(candidate))
    )


def nested_frontier_face(
    origin: Voxel,
    edge_a: Voxel,
    edge_b: Voxel,
    clip_bridges_to_polygon: bool = False,
    clip_logical_slices_to_polygon: bool = False,
    frontier_uses_unclipped_slice: bool = False,
    bridge_output_axis: bool = False,
    accept_output_axis_bridge: Callable[[Voxel], bool] | None = None,
) -> NestedFrontierFace:
    """Sweep one nested 3-D owned edge along an adjacent nested owned edge.

    Logical Pi is always an exact translation of the initial owned edge by a
    sweep offset.  A projected minor+major event emits a non-recursive bridge
    from the old Pi's +minor frontier, restored through the face plane.
    """
    normal = cross(
        tuple(float(value) for value in edge_a),
        tuple(float(value) for value in edge_b),
    )
    if length_squared(normal) <= EPSILON:
        raise ValueError("parallel edge vectors do not define a face")
    projection = Projection.for_normal(normal)
    origin_pixel = projection.project_voxel(origin)
    sweep_end = add_voxel(origin, edge_b)
    sweep_delta = (
        projection.project_voxel(sweep_end)[0] - origin_pixel[0],
        projection.project_voxel(sweep_end)[1] - origin_pixel[1],
    )
    projected_sweep = ProjectedSweep.from_delta(sweep_delta)

    initial = nested_bresenham_path(origin, add_voxel(origin, edge_a))
    sweep = nested_bresenham_path(origin, sweep_end)
    slices: list[frozenset[Voxel]] = []
    bridges: list[frozenset[Voxel]] = []
    output_axis_bridges: list[frozenset[Voxel]] = []
    result: set[Voxel] = set()
    previous_projected_offset: Pixel | None = None
    previous_slice: frozenset[Voxel] | None = None
    previous_frontier_source: frozenset[Voxel] | None = None
    previous_sweep_point: Voxel | None = None
    plane_origin = center(origin)
    projected_polygon = tuple(
        projection.project_point(vertex)
        for vertex in plane_vertices(origin, edge_a, edge_b)
    )

    def pixel_center_is_inside(pixel: Pixel) -> bool:
        return contains(
            projected_polygon,
            (pixel[0] + 0.5, pixel[1] + 0.5),
        )

    for sweep_index, sweep_point in enumerate(sweep):
        offset = (
            sweep_point[0] - origin[0],
            sweep_point[1] - origin[1],
            sweep_point[2] - origin[2],
        )
        translated = tuple(add_voxel(point, offset) for point in initial)
        frontier_source = frozenset(translated)
        if (
            clip_logical_slices_to_polygon
            and sweep_index not in (0, len(sweep) - 1)
        ):
            rail_endpoints = {translated[0], translated[-1]}
            logical = frozenset(
                voxel
                for voxel in translated
                if voxel in rail_endpoints
                or pixel_center_is_inside(projection.project_voxel(voxel))
            )
        else:
            logical = frozenset(translated)
        projected_offset = projection.project_voxel(sweep_point)
        slices.append(logical)
        result.update(logical)

        bridge: frozenset[Voxel] = frozenset()
        if (
            previous_slice is not None
            and previous_projected_offset is not None
            and projected_sweep.is_diagonal_event(previous_projected_offset, projected_offset)
        ):
            minor_offset = projected_sweep.minor_offset
            bridge_source = (
                previous_frontier_source
                if frontier_uses_unclipped_slice and previous_frontier_source is not None
                else previous_slice
            )
            previous_pixels = {
                projection.project_voxel(voxel)
                for voxel in bridge_source
            }
            frontier = positive_frontier(previous_pixels, minor_offset)
            bridge = frozenset(
                restore_bridge(
                    (pixel[0] + minor_offset[0], pixel[1] + minor_offset[1]),
                    projection,
                    plane_origin,
                    normal,
                )
                for pixel in frontier
                if not clip_bridges_to_polygon
                or pixel_center_is_inside(
                    (pixel[0] + minor_offset[0], pixel[1] + minor_offset[1])
                )
            )
            result.update(bridge)
        bridges.append(bridge)
        output_axis_bridge = frozenset()
        if (
            bridge_output_axis
            and previous_frontier_source is not None
            and previous_sweep_point is not None
        ):
            step = tuple(
                sweep_point[axis] - previous_sweep_point[axis]
                for axis in range(3)
            )
            output_axis_bridge = output_axis_bridge_layer(
                previous_frontier_source,
                logical,
                step,  # type: ignore[arg-type]
                projection,
                accept_output_axis_bridge,
            )
            result.update(output_axis_bridge)
        output_axis_bridges.append(output_axis_bridge)
        previous_slice = logical
        previous_frontier_source = frontier_source
        previous_projected_offset = projected_offset
        previous_sweep_point = sweep_point

    return NestedFrontierFace(
        voxels=frozenset(result),
        logical_slices=tuple(slices),
        bridge_layers=tuple(bridges),
        output_axis_bridge_layers=tuple(output_axis_bridges),
        owned_edges=owned_paths(origin, edge_a, edge_b),
        projection=projection,
    )


def canonical_nested_frontier_face(
    origin: Voxel,
    edge_a: Voxel,
    edge_b: Voxel,
    clip_bridges_to_polygon: bool = False,
    clip_logical_slices_to_polygon: bool = False,
    frontier_uses_unclipped_slice: bool = False,
    bridge_output_axis: bool = False,
    accept_output_axis_bridge: Callable[[Voxel], bool] | None = None,
) -> NestedFrontierFace:
    sweep_end = add_voxel(origin, edge_b)
    if origin <= sweep_end:
        return nested_frontier_face(
            origin,
            edge_a,
            edge_b,
            clip_bridges_to_polygon,
            clip_logical_slices_to_polygon,
            frontier_uses_unclipped_slice,
            bridge_output_axis,
            accept_output_axis_bridge,
        )
    return nested_frontier_face(
        sweep_end,
        edge_a,
        negate(edge_b),
        clip_bridges_to_polygon,
        clip_logical_slices_to_polygon,
        frontier_uses_unclipped_slice,
        bridge_output_axis,
        accept_output_axis_bridge,
    )


@dataclass(frozen=True)
class NestedFaceCoverage:
    holes: frozenset[Pixel]
    extras: frozenset[Pixel]
    missing_owned_edge_voxels: frozenset[Voxel]
    maximum_projection_column_thickness: int


def coverage(
    origin: Voxel,
    edge_a: Voxel,
    edge_b: Voxel,
    face: NestedFrontierFace,
) -> NestedFaceCoverage:
    vertices = plane_vertices(origin, edge_a, edge_b)
    expected = projected_cells(vertices, face.projection)
    expected.update(
        face.projection.project_voxel(voxel)
        for edge in face.owned_edges
        for voxel in edge
    )
    owned = frozenset(voxel for edge in face.owned_edges for voxel in edge)
    return NestedFaceCoverage(
        holes=frozenset(expected - face.projected_cells),
        extras=frozenset(face.projected_cells - expected),
        missing_owned_edge_voxels=frozenset(owned - face.voxels),
        maximum_projection_column_thickness=face.maximum_projection_column_thickness,
    )
