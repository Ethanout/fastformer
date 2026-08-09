from __future__ import annotations

import argparse
import itertools
import math
from dataclasses import dataclass
from typing import Iterable, Sequence

Vec3 = tuple[float, float, float]
Voxel = tuple[int, int, int]
Point2 = tuple[float, float]

EPSILON = 1.0e-9


def add(a: Vec3, b: Vec3) -> Vec3:
    return a[0] + b[0], a[1] + b[1], a[2] + b[2]


def subtract(a: Vec3, b: Vec3) -> Vec3:
    return a[0] - b[0], a[1] - b[1], a[2] - b[2]


def scale(value: Vec3, factor: float) -> Vec3:
    return value[0] * factor, value[1] * factor, value[2] * factor


def cross(a: Vec3, b: Vec3) -> Vec3:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def dot(a: Vec3, b: Vec3) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def length_squared(value: Vec3) -> float:
    return value[0] * value[0] + value[1] * value[1] + value[2] * value[2]


def normalize(value: Vec3) -> Vec3:
    length = math.sqrt(length_squared(value))
    return (0.0, 0.0, 0.0) if length <= EPSILON else scale(value, 1.0 / length)


def floor_stable(value: float) -> int:
    nearest = round(value)
    return math.floor(nearest if abs(value - nearest) <= EPSILON else value)


def containing(point: Vec3) -> Voxel:
    return tuple(floor_stable(component) for component in point)  # type: ignore[return-value]


def face_normal(vertices: Sequence[Vec3]) -> Vec3:
    if len(vertices) < 3:
        return 0.0, 0.0, 0.0
    origin = vertices[0]
    for first in range(1, len(vertices) - 1):
        for second in range(first + 1, len(vertices)):
            candidate = cross(subtract(vertices[first], origin), subtract(vertices[second], origin))
            if length_squared(candidate) > EPSILON:
                return candidate
    return 0.0, 0.0, 0.0


@dataclass(frozen=True)
class Projection:
    output_axis: int
    input_axes: tuple[int, int]

    @staticmethod
    def for_normal(normal: Vec3) -> "Projection":
        output = max(range(3), key=lambda axis: abs(normal[axis]))
        inputs = tuple(axis for axis in range(3) if axis != output)
        return Projection(output, (inputs[0], inputs[1]))

    def project_point(self, point: Vec3) -> Point2:
        return point[self.input_axes[0]], point[self.input_axes[1]]

    def project_voxel(self, voxel: Voxel) -> tuple[int, int]:
        return voxel[self.input_axes[0]], voxel[self.input_axes[1]]

    def restore(
        self,
        first: int,
        second: int,
        origin: Vec3,
        normal: Vec3,
    ) -> Voxel:
        first_center = first + 0.5
        second_center = second + 0.5
        output = origin[self.output_axis] - (
            normal[self.input_axes[0]] * (first_center - origin[self.input_axes[0]])
            + normal[self.input_axes[1]] * (second_center - origin[self.input_axes[1]])
        ) / normal[self.output_axis]
        coordinates = [0, 0, 0]
        coordinates[self.input_axes[0]] = first
        coordinates[self.input_axes[1]] = second
        coordinates[self.output_axis] = floor_stable(output)
        return coordinates[0], coordinates[1], coordinates[2]


def on_segment(a: Point2, b: Point2, point: Point2) -> bool:
    delta_first = b[0] - a[0]
    delta_second = b[1] - a[1]
    cross_value = (point[0] - a[0]) * delta_second - (point[1] - a[1]) * delta_first
    if abs(cross_value) > EPSILON:
        return False
    dot = (point[0] - a[0]) * delta_first + (point[1] - a[1]) * delta_second
    return -EPSILON <= dot <= delta_first * delta_first + delta_second * delta_second + EPSILON


def contains(polygon: Sequence[Point2], point: Point2) -> bool:
    inside = False
    previous = len(polygon) - 1
    for current in range(len(polygon)):
        a = polygon[previous]
        b = polygon[current]
        if on_segment(a, b, point):
            return True
        crosses = (a[1] > point[1]) != (b[1] > point[1])
        if crosses:
            intersection = a[0] + (point[1] - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
            if point[0] < intersection:
                inside = not inside
        previous = current
    return inside


def projected_cells(vertices: Sequence[Vec3], projection: Projection) -> set[tuple[int, int]]:
    polygon = [projection.project_point(vertex) for vertex in vertices]
    min_first = floor_stable(min(point[0] for point in polygon))
    max_first = floor_stable(max(point[0] for point in polygon))
    min_second = floor_stable(min(point[1] for point in polygon))
    max_second = floor_stable(max(point[1] for point in polygon))
    return {
        (first, second)
        for first in range(min_first, max_first + 1)
        for second in range(min_second, max_second + 1)
        if contains(polygon, (first + 0.5, second + 0.5))
    }


def bresenham_path_2d(start: tuple[int, int], end: tuple[int, int]) -> tuple[tuple[int, int], ...]:
    """Return a thin, reversal-stable 2-D Bresenham path.

    The major coordinate advances on every step.  The minor coordinate only
    advances together with it, so the path cannot contain a one-pixel L turn.
    Canonical endpoint ordering makes the selected tie pixels independent of
    the direction in which an edge is visited.
    """
    canonical_start, canonical_end = (start, end) if start <= end else (end, start)
    delta = (
        canonical_end[0] - canonical_start[0],
        canonical_end[1] - canonical_start[1],
    )
    distance = abs(delta[0]), abs(delta[1])
    primary = 0 if distance[0] >= distance[1] else 1
    secondary = 1 - primary
    steps = distance[primary]
    if steps == 0:
        return (canonical_start,)
    sign = (
        0 if delta[0] == 0 else (1 if delta[0] > 0 else -1),
        0 if delta[1] == 0 else (1 if delta[1] > 0 else -1),
    )
    result: list[tuple[int, int]] = []
    for step in range(steps + 1):
        progress = (step * distance[secondary] + steps // 2) // steps
        coordinates = [canonical_start[0], canonical_start[1]]
        coordinates[primary] += sign[primary] * step
        coordinates[secondary] += sign[secondary] * progress
        result.append((coordinates[0], coordinates[1]))
    if start != canonical_start:
        result.reverse()
    return tuple(result)


def nested_bresenham_path(start: Voxel, end: Voxel) -> tuple[Voxel, ...]:
    """Three-axis path whose lower-axis events are nested in higher ones."""
    canonical_start, canonical_end = (start, end) if start <= end else (end, start)
    delta = tuple(canonical_end[axis] - canonical_start[axis] for axis in range(3))
    distance = tuple(abs(value) for value in delta)
    axes = sorted(range(3), key=lambda axis: (-distance[axis], axis))
    primary, secondary, tertiary = axes
    steps = distance[primary]
    if steps == 0:
        return (canonical_start,)
    signs = tuple(0 if value == 0 else (1 if value > 0 else -1) for value in delta)
    result: list[Voxel] = []
    for step in range(steps + 1):
        secondary_progress = (step * distance[secondary] + steps // 2) // steps
        tertiary_steps = max(1, distance[secondary])
        tertiary_progress = (
            secondary_progress * distance[tertiary] + tertiary_steps // 2
        ) // tertiary_steps
        progress = [0, 0, 0]
        progress[primary] = step
        progress[secondary] = secondary_progress
        progress[tertiary] = tertiary_progress
        result.append(tuple(
            canonical_start[axis] + signs[axis] * progress[axis]
            for axis in range(3)
        ))
    if start != canonical_start:
        result.reverse()
    return tuple(result)


def worker_parallelogram_face(vertices: Sequence[Vec3]) -> set[Voxel]:
    """Fill a parallelogram by advancing two owned edge workers in lockstep."""
    if len(vertices) != 4:
        return set()
    cells = tuple(containing(vertex) for vertex in vertices)
    first_rail = nested_bresenham_path(cells[0], cells[3])
    second_rail = nested_bresenham_path(cells[1], cells[2])
    if len(first_rail) != len(second_rail):
        raise AssertionError("opposite parallelogram edges must have equal worker lengths")
    return {
        voxel
        for first, second in zip(first_rail, second_rail)
        for voxel in nested_bresenham_path(first, second)
    }


def scanline_worker_face(vertices: Sequence[Vec3]) -> tuple[set[Voxel], tuple[tuple[Voxel, ...], ...]]:
    """Fill a convex face from edge-worker events in its canonical projection.

    The returned edge paths retain ownership.  They are also the active-edge
    events used to choose each filled scan span, rather than an unrelated line
    set appended after surface rasterization.
    """
    if len(vertices) < 3:
        return set(), tuple()
    normal = face_normal(vertices)
    if length_squared(normal) <= EPSILON:
        return set(), tuple()
    projection = Projection.for_normal(normal)
    span_axis, scan_axis = projection.input_axes
    edge_paths = tuple(
        nested_bresenham_path(
            containing(vertices[index]),
            containing(vertices[(index + 1) % len(vertices)]),
        )
        for index in range(len(vertices))
    )
    rows: dict[int, list[Voxel]] = {}
    for path in edge_paths:
        for voxel in path:
            rows.setdefault(voxel[scan_axis], []).append(voxel)

    result: set[Voxel] = set()
    for row, events in rows.items():
        minimum = min(voxel[span_axis] for voxel in events)
        maximum = max(voxel[span_axis] for voxel in events)
        left_events = [voxel for voxel in events if voxel[span_axis] == minimum]
        right_events = [voxel for voxel in events if voxel[span_axis] == maximum]

        def representative(candidates: Sequence[Voxel], span: int) -> Voxel:
            expected = projection.restore(span, row, vertices[0], normal)
            return min(
                candidates,
                key=lambda voxel: sum(
                    (voxel[axis] - expected[axis]) ** 2
                    for axis in range(3)
                ),
            )

        left = representative(left_events, minimum)
        right = representative(right_events, maximum)
        result.update(nested_bresenham_path(left, right))

    # Active-edge events are part of the scan conversion.  Keeping them is
    # necessary when a steep owned edge has more than one voxel in one
    # projected cell; otherwise the face silently changes its outline.
    result.update(voxel for path in edge_paths for voxel in path)
    return result, edge_paths


def projected_boundary_cells(
    vertices: Sequence[Vec3], projection: Projection
) -> set[tuple[int, int]]:
    """Rasterize polygon edges in the face's canonical coordinate plane."""
    projected_vertices = [
        projection.project_voxel(containing(vertex))
        for vertex in vertices
    ]
    result: set[tuple[int, int]] = set()
    for index, start in enumerate(projected_vertices):
        result.update(bresenham_path_2d(start, projected_vertices[(index + 1) % len(projected_vertices)]))
    return result


def projected_edge_path(
    start: Vec3,
    end: Vec3,
    plane_origin: Vec3,
    normal: Vec3,
    projection: Projection,
) -> tuple[Voxel, ...]:
    """Rasterize one owned face edge through the face's 2-D projection."""
    start_cell = projection.project_voxel(containing(start))
    end_cell = projection.project_voxel(containing(end))
    return tuple(
        projection.restore(first, second, plane_origin, normal)
        for first, second in bresenham_path_2d(start_cell, end_cell)
    )


def bresenham_projected_face(
    vertices: Sequence[Vec3], max_voxels: int | None = None
) -> set[Voxel]:
    """Extend edge Bresenham into a dominant-projection planar raster.

    Only the two-dimensional lattice is rasterized.  Every selected lattice
    cell is restored once through the plane equation; edge voxels are not
    unioned into the result as a separate three-dimensional line set.
    """
    normal = face_normal(vertices)
    if length_squared(normal) <= EPSILON or max_voxels is not None and max_voxels <= 0:
        return set()
    projection = Projection.for_normal(normal)
    lattice = projected_cells(vertices, projection)
    lattice.update(projected_boundary_cells(vertices, projection))
    result: set[Voxel] = set()
    for first, second in sorted(lattice):
        if max_voxels is not None and len(result) >= max_voxels:
            break
        result.add(projection.restore(first, second, vertices[0], normal))
    return result


def dominant_axis_face(vertices: Sequence[Vec3], max_voxels: int | None = None) -> set[Voxel]:
    normal = face_normal(vertices)
    if length_squared(normal) <= EPSILON or max_voxels is not None and max_voxels <= 0:
        return set()
    projection = Projection.for_normal(normal)
    result: set[Voxel] = set()
    for first, second in sorted(projected_cells(vertices, projection)):
        if max_voxels is not None and len(result) >= max_voxels:
            break
        result.add(projection.restore(first, second, vertices[0], normal))
    return result


def line_voxels(start: Vec3, end: Vec3) -> set[Voxel]:
    delta = subtract(end, start)
    steps = max(1, math.ceil(max(abs(component) for component in delta)))
    return {containing(add(start, scale(delta, step / steps))) for step in range(steps + 1)}


def current_quad_sweep(vertices: Sequence[Vec3]) -> set[Voxel]:
    if len(vertices) != 4:
        return set()
    first, second, opposite_second, opposite_first = vertices
    first_delta = subtract(opposite_first, first)
    second_delta = subtract(opposite_second, second)
    rows = max(
        1,
        math.ceil(max(abs(component) for component in first_delta)),
        math.ceil(max(abs(component) for component in second_delta)),
    )
    result: set[Voxel] = set()
    for row in range(rows + 1):
        ratio = row / rows
        result.update(line_voxels(add(first, scale(first_delta, ratio)), add(second, scale(second_delta, ratio))))
    return result


@dataclass(frozen=True)
class Coverage:
    holes: frozenset[tuple[int, int]]
    extras: frozenset[tuple[int, int]]
    maximum_column_thickness: int


def coverage(vertices: Sequence[Vec3], voxels: Iterable[Voxel]) -> Coverage:
    normal = face_normal(vertices)
    projection = Projection.for_normal(normal)
    expected = projected_cells(vertices, projection)
    counts: dict[tuple[int, int], int] = {}
    for voxel in voxels:
        cell = projection.project_voxel(voxel)
        counts[cell] = counts.get(cell, 0) + 1
    actual = set(counts)
    return Coverage(
        frozenset(expected - actual),
        frozenset(actual - expected),
        max(counts.values(), default=0),
    )


def parallelogram(first: Vec3, edge_a: Vec3, edge_b: Vec3) -> tuple[Vec3, Vec3, Vec3, Vec3]:
    second = add(first, edge_a)
    fourth = add(first, edge_b)
    return first, second, add(second, edge_b), fourth


def find_current_sweep_witness(limit: int = 3) -> tuple[tuple[Vec3, ...], Coverage]:
    origin = (0.5, 0.5, 0.5)
    vectors = [
        tuple(float(value) for value in vector)
        for vector in itertools.product(range(-limit, limit + 1), repeat=3)
        if vector != (0, 0, 0)
    ]
    best_vertices: tuple[Vec3, ...] | None = None
    best_coverage: Coverage | None = None
    for edge_a in vectors:
        for edge_b in vectors:
            if length_squared(cross(edge_a, edge_b)) <= EPSILON:
                continue
            vertices = parallelogram(origin, edge_a, edge_b)
            result = coverage(vertices, current_quad_sweep(vertices))
            if best_coverage is None or len(result.holes) > len(best_coverage.holes):
                best_vertices = vertices
                best_coverage = result
    if best_vertices is None or best_coverage is None:
        raise AssertionError("no non-degenerate parallelogram found")
    return best_vertices, best_coverage


def ascii_projection(vertices: Sequence[Vec3], voxels: Iterable[Voxel]) -> str:
    normal = face_normal(vertices)
    projection = Projection.for_normal(normal)
    expected = projected_cells(vertices, projection)
    actual = {projection.project_voxel(voxel) for voxel in voxels}
    all_cells = expected | actual
    min_first = min(cell[0] for cell in all_cells)
    max_first = max(cell[0] for cell in all_cells)
    min_second = min(cell[1] for cell in all_cells)
    max_second = max(cell[1] for cell in all_cells)
    rows: list[str] = []
    for second in range(max_second, min_second - 1, -1):
        row = []
        for first in range(min_first, max_first + 1):
            cell = first, second
            row.append("#" if cell in expected and cell in actual else "O" if cell in expected else "X" if cell in actual else " ")
        rows.append("".join(row))
    return "\n".join(rows)


def report() -> None:
    vertices, old_coverage = find_current_sweep_witness()
    candidate = dominant_axis_face(vertices)
    candidate_coverage = coverage(vertices, candidate)
    print("witness vertices:")
    for vertex in vertices:
        print(f"  {vertex}")
    print(
        f"old holes={len(old_coverage.holes)} extras={len(old_coverage.extras)} "
        f"thickness={old_coverage.maximum_column_thickness}"
    )
    print(ascii_projection(vertices, current_quad_sweep(vertices)))
    print(
        f"candidate holes={len(candidate_coverage.holes)} extras={len(candidate_coverage.extras)} "
        f"thickness={candidate_coverage.maximum_column_thickness}"
    )
    print(ascii_projection(vertices, candidate))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", action="store_true")
    arguments = parser.parse_args()
    if arguments.report:
        report()
