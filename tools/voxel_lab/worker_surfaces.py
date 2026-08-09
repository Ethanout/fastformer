from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Sequence

from planar_faces import (
    EPSILON,
    Projection,
    Vec3,
    Voxel,
    bresenham_path_2d,
    containing,
    face_normal,
    length_squared,
    nested_bresenham_path,
    projected_cells,
)

Pixel = tuple[int, int]

NEIGHBORS: tuple[Voxel, ...] = (
    (-1, 0, 0),
    (1, 0, 0),
    (0, -1, 0),
    (0, 1, 0),
    (0, 0, -1),
    (0, 0, 1),
)


def add_voxel(first: Voxel, second: Voxel) -> Voxel:
    return tuple(first[axis] + second[axis] for axis in range(3))  # type: ignore[return-value]


@dataclass(frozen=True)
class OwnedEdge:
    """One geometric edge and only the pixels selected for that edge."""

    index: int
    start_vertex: int
    end_vertex: int
    pixels: tuple[Pixel, ...]

    @property
    def interior(self) -> tuple[Pixel, ...]:
        return self.pixels[1:-1]


@dataclass(frozen=True)
class WorkerFace:
    projection: Projection
    vertices: tuple[Pixel, ...]
    edges: tuple[OwnedEdge, ...]
    cells: frozenset[Pixel]
    rail_pair: int

    @property
    def boundary(self) -> frozenset[Pixel]:
        return frozenset(pixel for edge in self.edges for pixel in edge.pixels)


def projected_vertex_cells(vertices: Sequence[Vec3], projection: Projection) -> tuple[Pixel, ...]:
    return tuple(projection.project_voxel(containing(vertex)) for vertex in vertices)


def owned_edges(vertices: Sequence[Pixel]) -> tuple[OwnedEdge, ...]:
    return tuple(
        OwnedEdge(
            index=index,
            start_vertex=index,
            end_vertex=(index + 1) % len(vertices),
            pixels=bresenham_path_2d(vertices[index], vertices[(index + 1) % len(vertices)]),
        )
        for index in range(len(vertices))
    )


def synchronized_workers(
    first: Sequence[Pixel], second: Sequence[Pixel]
) -> tuple[tuple[Pixel, Pixel], ...]:
    """Advance both complete rail paths over one shared integer clock.

    The clock is at least as long as either path, so no owned rail pixel is
    skipped when the opposite edges have different digital lengths.
    """
    first_steps = len(first) - 1
    second_steps = len(second) - 1
    clock = max(first_steps, second_steps)
    if clock == 0:
        return ((first[0], second[0]),)

    def sample(path: Sequence[Pixel], steps: int, tick: int) -> Pixel:
        index = (tick * steps + clock // 2) // clock
        return path[index]

    return tuple(
        (sample(first, first_steps, tick), sample(second, second_steps, tick))
        for tick in range(clock + 1)
    )


def lockstep_quad_lattice(projected_vertices: Sequence[Pixel], rail_pair: int) -> WorkerFace:
    """Fill an ordered quad by synchronizing one pair of opposite edge workers.

    rail_pair=0 uses edges 3 and 1 as rails; rail_pair=1 uses edges 0 and 2.
    Every worker tick is filled by the same thin 2-D line primitive.
    """
    if len(projected_vertices) != 4:
        raise ValueError("lockstep quad fill requires four vertices")
    if rail_pair not in (0, 1):
        raise ValueError("rail_pair must be 0 or 1")
    vertices = tuple(projected_vertices)
    edges = owned_edges(vertices)
    if rail_pair == 0:
        first_rail = tuple(reversed(edges[3].pixels))
        second_rail = edges[1].pixels
    else:
        first_rail = edges[0].pixels
        second_rail = tuple(reversed(edges[2].pixels))
    cells = {
        pixel
        for first, second in synchronized_workers(first_rail, second_rail)
        for pixel in bresenham_path_2d(first, second)
    }
    # The result is a face raster, not a union with independently restored 3-D
    # edge voxels.  Keeping the 2-D ownership data still makes corner-only L/T
    # validation possible.
    cells.update(pixel for edge in edges for pixel in edge.pixels)
    return WorkerFace(
        projection=Projection(2, (0, 1)),
        vertices=vertices,
        edges=edges,
        cells=frozenset(cells),
        rail_pair=rail_pair,
    )


def projected_lockstep_face(vertices: Sequence[Vec3], rail_pair: int) -> WorkerFace:
    normal = face_normal(vertices)
    if length_squared(normal) <= EPSILON:
        raise ValueError("face must be non-degenerate")
    projection = Projection.for_normal(normal)
    candidate = lockstep_quad_lattice(projected_vertex_cells(vertices, projection), rail_pair)
    return WorkerFace(
        projection=projection,
        vertices=candidate.vertices,
        edges=candidate.edges,
        cells=candidate.cells,
        rail_pair=rail_pair,
    )


def boundary_scan_quad_lattice(projected_vertices: Sequence[Pixel]) -> WorkerFace:
    """Fill a convex quad from four owned Bresenham boundary workers.

    Each projected row is filled between the first and last boundary event.
    Unlike the sparse lockstep interpretation, this fills the complete strip
    swept since the preceding worker events, including parity cells between
    two simultaneously diagonal advances.
    """
    if len(projected_vertices) != 4:
        raise ValueError("boundary scan fill requires four vertices")
    vertices = tuple(projected_vertices)
    edges = owned_edges(vertices)
    boundary = {pixel for edge in edges for pixel in edge.pixels}
    by_row: dict[int, list[int]] = {}
    for first, second in boundary:
        by_row.setdefault(second, []).append(first)
    cells = {
        (first, second)
        for second, intersections in by_row.items()
        for first in range(min(intersections), max(intersections) + 1)
    }
    return WorkerFace(
        projection=Projection(2, (0, 1)),
        vertices=vertices,
        edges=edges,
        cells=frozenset(cells),
        rail_pair=-1,
    )


def projected_boundary_scan_face(vertices: Sequence[Vec3]) -> WorkerFace:
    normal = face_normal(vertices)
    if length_squared(normal) <= EPSILON:
        raise ValueError("face must be non-degenerate")
    projection = Projection.for_normal(normal)
    candidate = boundary_scan_quad_lattice(projected_vertex_cells(vertices, projection))
    return WorkerFace(
        projection=projection,
        vertices=candidate.vertices,
        edges=candidate.edges,
        cells=candidate.cells,
        rail_pair=candidate.rail_pair,
    )


def restore_face(vertices: Sequence[Vec3], worker: WorkerFace) -> set[Voxel]:
    normal = face_normal(vertices)
    return {
        worker.projection.restore(first, second, vertices[0], normal)
        for first, second in worker.cells
    }


def expected_projected_face(vertices: Sequence[Vec3]) -> set[Pixel]:
    normal = face_normal(vertices)
    projection = Projection.for_normal(normal)
    result = projected_cells(vertices, projection)
    result.update(pixel for edge in owned_edges(projected_vertex_cells(vertices, projection)) for pixel in edge.pixels)
    return result


def right_angle_at(pixels: set[Pixel] | frozenset[Pixel], center: Pixel) -> bool:
    offsets = [
        (first, second)
        for first in (-1, 0, 1)
        for second in (-1, 0, 1)
        if (first != 0 or second != 0)
        and (center[0] + first, center[1] + second) in pixels
    ]
    return any(
        first[0] * second[0] + first[1] * second[1] == 0
        for index, first in enumerate(offsets)
        for second in offsets[index + 1 :]
    )


def owned_edge_internal_l_pixels(edge: OwnedEdge) -> frozenset[Pixel]:
    pixels = set(edge.pixels)
    return frozenset(pixel for pixel in edge.interior if right_angle_at(pixels, pixel))


def six_neighbor_boundary(solid: set[Voxel]) -> set[Voxel]:
    return {
        voxel
        for voxel in solid
        if any(add_voxel(voxel, neighbor) not in solid for neighbor in NEIGHBORS)
    }


def worker_parallelepiped(
    origin: Voxel, edge_a: Voxel, edge_b: Voxel, edge_c: Voxel
) -> tuple[set[Voxel], set[Voxel], tuple[frozenset[Voxel], ...]]:
    """Return a three-worker solid, its six digital faces, and 12 owned edges.

    This is deliberately the strongest possible interpretation of nested
    worker sweeping: every voxel is the Minkowski sum of one event from each
    edge worker.  It lets the lab compare independently generated worker faces
    against the solid's actual six-neighbor boundary.
    """
    zero = (0, 0, 0)
    paths = (
        nested_bresenham_path(zero, edge_a),
        nested_bresenham_path(zero, edge_b),
        nested_bresenham_path(zero, edge_c),
    )
    solid = {
        add_voxel(origin, add_voxel(first, add_voxel(second, third)))
        for first in paths[0]
        for second in paths[1]
        for third in paths[2]
    }
    faces: list[set[Voxel]] = []
    for fixed_axis in range(3):
        moving_axes = tuple(axis for axis in range(3) if axis != fixed_axis)
        for endpoint in (paths[fixed_axis][0], paths[fixed_axis][-1]):
            faces.append({
                add_voxel(origin, add_voxel(endpoint, add_voxel(first, second)))
                for first in paths[moving_axes[0]]
                for second in paths[moving_axes[1]]
            })
    edge_sets: list[frozenset[Voxel]] = []
    for moving_axis in range(3):
        fixed_axes = tuple(axis for axis in range(3) if axis != moving_axis)
        for first_endpoint in (paths[fixed_axes[0]][0], paths[fixed_axes[0]][-1]):
            for second_endpoint in (paths[fixed_axes[1]][0], paths[fixed_axes[1]][-1]):
                edge_sets.append(frozenset(
                    add_voxel(
                        origin,
                        add_voxel(first_endpoint, add_voxel(second_endpoint, point)),
                    )
                    for point in paths[moving_axis]
                ))
    return solid, set().union(*faces), tuple(edge_sets)


@dataclass(frozen=True)
class FaceCoverage:
    holes: frozenset[Pixel]
    extras: frozenset[Pixel]


def face_coverage(vertices: Sequence[Vec3], worker: WorkerFace) -> FaceCoverage:
    expected = expected_projected_face(vertices)
    return FaceCoverage(
        holes=frozenset(expected - worker.cells),
        extras=frozenset(worker.cells - expected),
    )


def extent(voxels: Iterable[Voxel]) -> tuple[tuple[int, int], ...]:
    materialized = tuple(voxels)
    return tuple(
        (min(voxel[axis] for voxel in materialized), max(voxel[axis] for voxel in materialized))
        for axis in range(3)
    )
