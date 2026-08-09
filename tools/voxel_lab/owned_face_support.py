"""Architectural experiment for owned-edge planar faces.

The logical face is restored once from one analytic plane.  Exact 3-D
LineGenerator edges are then added as immutable column constraints.  Physical
six-neighbour support is selected on one face-wide side; no 2x2 repair is
allowed to raise and lower unrelated parts of the visible envelope.
"""
from __future__ import annotations

from collections import Counter, deque
from dataclasses import dataclass
from typing import Iterable, Sequence

from bresenham_scan_lab import canonical_frame, lift, scan_face
from minimal_surface_bridge import close_projection
from planar_faces import Vec3, Voxel, containing, face_normal, nested_bresenham_path

Pixel = tuple[int, int]
NEIGHBORS_3D: tuple[Voxel, ...] = (
    (-1, 0, 0), (1, 0, 0),
    (0, -1, 0), (0, 1, 0),
    (0, 0, -1), (0, 0, 1),
)


@dataclass(frozen=True)
class FaceMetrics:
    blocks: int
    columns: int
    added_support: int
    maximum_thickness: int
    maximum_interior_thickness: int
    upper_pits: int
    lower_pits: int
    upper_pits_all: int
    lower_pits_all: int
    upper_basin_cells: int
    lower_basin_cells: int
    upper_three_one: int
    lower_three_one: int
    six_connected: bool


@dataclass(frozen=True)
class SupportedFace:
    blocks: frozenset[Voxel]
    logical: frozenset[Voxel]
    outline: frozenset[Voxel]
    support: frozenset[Voxel]
    side: str
    metrics: FaceMetrics


def _local(frame, voxel: Voxel) -> tuple[int, int, int]:
    value = frame.vector(tuple(component + 0.5 for component in voxel))
    return round(value[0]), round(value[1]), round(value[2])


def _columns(frame, points: Iterable[Voxel]) -> dict[Pixel, set[int]]:
    result: dict[Pixel, set[int]] = {}
    for point in points:
        u, v, w = _local(frame, point)
        result.setdefault((u, v), set()).add(w)
    return result


def _fill_spans(columns: dict[Pixel, set[int]]) -> None:
    for heights in columns.values():
        heights.update(range(min(heights), max(heights) + 1))


def _exact_outline(vertices: Sequence[Vec3]) -> set[Voxel]:
    cells = tuple(containing(vertex) for vertex in vertices)
    return {
        point
        for index, start in enumerate(cells)
        for point in nested_bresenham_path(start, cells[(index + 1) % len(cells)])
    }


def _base_columns(vertices: Sequence[Vec3]):
    scanned, _, frame = scan_face(vertices)
    _, local_vertices = canonical_frame(vertices)
    normal = face_normal(local_vertices)
    scanned_cells = {(_local(frame, point)[0], _local(frame, point)[1]) for point in scanned}
    domain = close_projection(scanned_cells, local_vertices)
    logical = {frame.world_voxel(lift(cell, normal)) for cell in domain}
    outline = _exact_outline(vertices)
    columns = _columns(frame, logical | outline)
    _fill_spans(columns)
    return logical, outline, columns, frame


def _connected_columns(
    source: dict[Pixel, set[int]], side: str,
    normal: tuple[float, float, float] | None = None,
) -> tuple[dict[Pixel, set[int]], set[tuple[int, int, int]]]:
    if side not in ("lower", "upper", "normal"):
        raise ValueError(side)
    if side == "normal" and normal is None:
        raise ValueError("normal-aware support requires a face normal")
    snapshot = {cell: set(heights) for cell, heights in source.items()}
    result = {cell: set(heights) for cell, heights in source.items()}
    support: set[tuple[int, int, int]] = set()
    for cell in sorted(snapshot):
        for step in ((1, 0), (0, 1)):
            neighbor = cell[0] + step[0], cell[1] + step[1]
            if neighbor not in snapshot:
                continue
            first, second = snapshot[cell], snapshot[neighbor]
            if max(first) < min(second):
                low_cell, low, high_cell, high = cell, first, neighbor, second
            elif max(second) < min(first):
                low_cell, low, high_cell, high = neighbor, second, cell, first
            else:
                continue
            if side == "lower":
                target, destination = max(low), high_cell
            elif side == "upper":
                target, destination = min(high), low_cell
            else:
                candidates = ((high_cell, max(low)), (low_cell, min(high)))
                destination, target = min(
                    candidates,
                    key=lambda candidate: (
                        abs(
                            normal[0] * candidate[0][0]
                            + normal[1] * candidate[0][1]
                            + normal[2] * candidate[1]
                        ),
                        candidate,
                    ),
                )
            result[destination].add(target)
            support.add((destination[0], destination[1], target))
    _fill_spans(result)
    return result, support


def _strict_pits(envelope: dict[Pixel, int], upper: bool, excluded: set[Pixel]) -> set[Pixel]:
    result = set()
    for cell, height in envelope.items():
        if cell in excluded:
            continue
        neighbors = (
            (cell[0] + 1, cell[1]), (cell[0] - 1, cell[1]),
            (cell[0], cell[1] + 1), (cell[0], cell[1] - 1),
        )
        if all(neighbor in envelope for neighbor in neighbors):
            values = [envelope[neighbor] for neighbor in neighbors]
            if upper and all(height < value for value in values):
                result.add(cell)
            if not upper and all(height > value for value in values):
                result.add(cell)
    return result


def _three_one(envelope: dict[Pixel, int]) -> int:
    result = 0
    for u, v in envelope:
        square = ((u, v), (u + 1, v), (u, v + 1), (u + 1, v + 1))
        if not all(cell in envelope for cell in square):
            continue
        values = [envelope[cell] for cell in square]
        low, high = min(values), max(values)
        if high - low == 1 and values.count(low) in (1, 3):
            result += 1
    return result


def _enclosed_basin_cells(envelope: dict[Pixel, int], upper: bool) -> set[Pixel]:
    domain = set(envelope)
    boundary = {
        cell for cell in domain
        if any(neighbor not in domain for neighbor in (
            (cell[0] + 1, cell[1]), (cell[0] - 1, cell[1]),
            (cell[0], cell[1] + 1), (cell[0], cell[1] - 1),
        ))
    }
    result: set[Pixel] = set()
    low, high = min(envelope.values()), max(envelope.values())
    for threshold in range(low, high + 1):
        selected = {
            cell for cell, value in envelope.items()
            if value < threshold if upper
        } if upper else {
            cell for cell, value in envelope.items()
            if value > threshold
        }
        unseen = set(selected)
        while unseen:
            start = unseen.pop()
            component = {start}
            queue = [start]
            while queue:
                cell = queue.pop()
                for neighbor in (
                    (cell[0] + 1, cell[1]), (cell[0] - 1, cell[1]),
                    (cell[0], cell[1] + 1), (cell[0], cell[1] - 1),
                ):
                    if neighbor in unseen:
                        unseen.remove(neighbor)
                        component.add(neighbor)
                        queue.append(neighbor)
            if component.isdisjoint(boundary):
                result.update(component)
    return result


def _six_connected(points: set[Voxel]) -> bool:
    if not points:
        return True
    seen: set[Voxel] = set()
    queue = deque((next(iter(points)),))
    while queue:
        point = queue.popleft()
        if point in seen:
            continue
        seen.add(point)
        for offset in NEIGHBORS_3D:
            neighbor = tuple(point[axis] + offset[axis] for axis in range(3))
            if neighbor in points and neighbor not in seen:
                queue.append(neighbor)  # type: ignore[arg-type]
    return len(seen) == len(points)


def _materialize(columns, frame) -> set[Voxel]:
    return {
        frame.world_voxel((cell[0], cell[1], height))
        for cell, heights in columns.items()
        for height in heights
    }


def _metrics(columns, blocks, support, outline_columns) -> FaceMetrics:
    upper = {cell: max(heights) for cell, heights in columns.items()}
    lower = {cell: min(heights) for cell, heights in columns.items()}
    interior = [len(heights) for cell, heights in columns.items() if cell not in outline_columns]
    return FaceMetrics(
        blocks=len(blocks),
        columns=len(columns),
        added_support=len(support),
        maximum_thickness=max(map(len, columns.values()), default=0),
        maximum_interior_thickness=max(interior, default=0),
        upper_pits=len(_strict_pits(upper, True, outline_columns)),
        lower_pits=len(_strict_pits(lower, False, outline_columns)),
        upper_pits_all=len(_strict_pits(upper, True, set())),
        lower_pits_all=len(_strict_pits(lower, False, set())),
        upper_basin_cells=len(_enclosed_basin_cells(upper, True)),
        lower_basin_cells=len(_enclosed_basin_cells(lower, False)),
        upper_three_one=_three_one(upper),
        lower_three_one=_three_one(lower),
        six_connected=_six_connected(blocks),
    )


def supported_owned_face(vertices: Sequence[Vec3], side: str = "auto") -> SupportedFace:
    logical, outline, base, frame = _base_columns(vertices)
    _, local_vertices = canonical_frame(vertices)
    normal = face_normal(local_vertices)
    outline_columns = set(_columns(frame, outline))
    candidates = []
    for candidate_side in (("lower", "upper", "normal") if side == "auto" else (side,)):
        columns, local_support = _connected_columns(base, candidate_side, normal)
        blocks = _materialize(columns, frame)
        support = blocks - logical - outline
        metrics = _metrics(columns, blocks, support, outline_columns)
        normal_error = sum(
            abs(normal[0] * cell[0] + normal[1] * cell[1] + normal[2] * height)
            for cell, heights in columns.items()
            for height in heights - base[cell]
        )
        candidates.append((
            (
                metrics.upper_basin_cells + metrics.lower_basin_cells,
                metrics.upper_pits + metrics.lower_pits,
                metrics.maximum_interior_thickness,
                normal_error,
                metrics.added_support,
                candidate_side,
            ),
            SupportedFace(
                frozenset(blocks), frozenset(logical), frozenset(outline),
                frozenset(support), candidate_side, metrics,
            ),
        ))
    return min(candidates, key=lambda item: item[0])[1]


def local_symmetric_face(vertices: Sequence[Vec3]) -> SupportedFace:
    """Approximate the current Java 2x2 symmetric-slab architecture."""
    logical, outline, base, frame = _base_columns(vertices)
    outline_columns = set(_columns(frame, outline))
    occupied, _ = _connected_columns(base, "lower")
    snapshot = {cell: set(heights) for cell, heights in base.items()}
    for u, v in sorted(snapshot):
        square = ((u, v), (u + 1, v), (u, v + 1), (u + 1, v + 1))
        if not all(cell in snapshot for cell in square):
            continue
        for upper in (False, True):
            values = [max(snapshot[cell]) if upper else min(snapshot[cell]) for cell in square]
            low, high = min(values), max(values)
            if high - low != 1 or values.count(low) not in (1, 3):
                continue
            for cell in square:
                occupied[cell].update((low, high))
    _fill_spans(occupied)
    blocks = _materialize(occupied, frame)
    support = blocks - logical - outline
    metrics = _metrics(occupied, blocks, support, outline_columns)
    return SupportedFace(
        frozenset(blocks), frozenset(logical), frozenset(outline),
        frozenset(support), "symmetric-2x2", metrics,
    )
