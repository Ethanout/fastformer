from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Iterable

from planar_faces import bresenham_path_2d
from worker_surfaces import OwnedEdge, owned_edges

Pixel = tuple[int, int]


def add_pixel(first: Pixel, second: Pixel) -> Pixel:
    return first[0] + second[0], first[1] + second[1]


def shift(points: Iterable[Pixel], offset: Pixel) -> frozenset[Pixel]:
    return frozenset(add_pixel(point, offset) for point in points)


def positive_x_frontier(points: Iterable[Pixel]) -> frozenset[Pixel]:
    materialized = frozenset(points)
    return frozenset(
        point
        for point in materialized
        if (point[0] + 1, point[1]) not in materialized
    )


@dataclass(frozen=True)
class SweepFrame:
    origin: Pixel
    major_axis: int
    minor_axis: int
    major_sign: int
    minor_sign: int

    @staticmethod
    def for_edge(origin: Pixel, end: Pixel, tie_major_axis: int = 1) -> "SweepFrame":
        delta = end[0] - origin[0], end[1] - origin[1]
        distance = abs(delta[0]), abs(delta[1])
        if distance[0] == distance[1]:
            major = tie_major_axis
        else:
            major = 0 if distance[0] > distance[1] else 1
        minor = 1 - major
        return SweepFrame(
            origin=origin,
            major_axis=major,
            minor_axis=minor,
            major_sign=1 if delta[major] >= 0 else -1,
            minor_sign=1 if delta[minor] >= 0 else -1,
        )

    def to_local(self, point: Pixel) -> Pixel:
        delta = point[0] - self.origin[0], point[1] - self.origin[1]
        return (
            delta[self.minor_axis] * self.minor_sign,
            delta[self.major_axis] * self.major_sign,
        )

    def from_local(self, point: Pixel) -> Pixel:
        coordinates = [self.origin[0], self.origin[1]]
        coordinates[self.minor_axis] += point[0] * self.minor_sign
        coordinates[self.major_axis] += point[1] * self.major_sign
        return coordinates[0], coordinates[1]


@dataclass(frozen=True)
class FrontierScan:
    cells: frozenset[Pixel]
    emitted_layers: tuple[frozenset[Pixel], ...]
    owned_edges: tuple[OwnedEdge, ...]
    frame: SweepFrame

    @property
    def boundary(self) -> frozenset[Pixel]:
        return frozenset(pixel for edge in self.owned_edges for pixel in edge.pixels)


Advance = Callable[[frozenset[Pixel], Pixel], tuple[frozenset[Pixel], frozenset[Pixel]]]


def recursive_pi_advance(
    current: frozenset[Pixel], step: Pixel
) -> tuple[frozenset[Pixel], frozenset[Pixel]]:
    """Literal reading: both generated parts become the next recursive Pi(k)."""
    if step == (0, 1):
        next_pi = shift(current, (0, 1))
    elif step == (1, 1):
        next_pi = shift(current, (0, 1)) | shift(positive_x_frontier(current), (1, 0))
    else:
        raise ValueError(f"local sweep step must be +y or +x+y, got {step}")
    return next_pi, next_pi


def logical_slice_bridge_advance(
    current: frozenset[Pixel], step: Pixel
) -> tuple[frozenset[Pixel], frozenset[Pixel]]:
    """Alternative reading: bridge points are emitted but not recursively moved.

    The logical Pi advances to its geometrically translated location.  On a
    diagonal event, old +x frontier points provide the missing orthogonal
    bridge while the complete Pi advances by +x+y.
    """
    next_pi = shift(current, step)
    emitted = next_pi
    if step == (1, 1):
        emitted |= shift(positive_x_frontier(current), (1, 0))
    return next_pi, emitted


def scan_parallelogram(
    origin: Pixel,
    span_end: Pixel,
    sweep_end: Pixel,
    advance: Advance = recursive_pi_advance,
    tie_major_axis: int = 1,
) -> FrontierScan:
    """Sweep one owned Bresenham span along an adjacent owned edge."""
    frame = SweepFrame.for_edge(origin, sweep_end, tie_major_axis)
    initial = frozenset(
        frame.to_local(pixel)
        for pixel in bresenham_path_2d(origin, span_end)
    )
    local_sweep = tuple(
        frame.to_local(pixel)
        for pixel in bresenham_path_2d(origin, sweep_end)
    )
    current = initial
    layers = [initial]
    covered = set(initial)
    for previous, following in zip(local_sweep, local_sweep[1:]):
        step = following[0] - previous[0], following[1] - previous[1]
        current, emitted = advance(current, step)
        layers.append(emitted)
        covered.update(emitted)

    world_cells = frozenset(frame.from_local(pixel) for pixel in covered)
    world_layers = tuple(
        frozenset(frame.from_local(pixel) for pixel in layer)
        for layer in layers
    )
    sweep_delta = sweep_end[0] - origin[0], sweep_end[1] - origin[1]
    opposite_span_end = add_pixel(span_end, sweep_delta)
    vertices = origin, span_end, opposite_span_end, sweep_end
    return FrontierScan(
        cells=world_cells,
        emitted_layers=world_layers,
        owned_edges=owned_edges(vertices),
        frame=frame,
    )


def reverse_sweep(
    origin: Pixel, span_end: Pixel, sweep_end: Pixel
) -> tuple[Pixel, Pixel, Pixel]:
    delta = sweep_end[0] - origin[0], sweep_end[1] - origin[1]
    reverse_origin = sweep_end
    reverse_span = add_pixel(span_end, delta)
    return reverse_origin, reverse_span, origin


def swap_axes(pixel: Pixel) -> Pixel:
    return pixel[1], pixel[0]
