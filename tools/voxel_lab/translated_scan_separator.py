"""Translated nested-Bresenham rails, interpolation, and a global air cut.

This is a Minecraft-independent reference implementation.  It deliberately
keeps the exact four 3-D edge paths, predicts the projected domain in one pass,
and chooses the final thin surface as a lower/upper-air vertex separator.
"""
from __future__ import annotations

from collections import defaultdict, deque
from dataclasses import dataclass
from fractions import Fraction
from heapq import heappop, heappush
from itertools import product
from math import ceil, floor

import networkx as nx


Vec3 = tuple[int, int, int]
Pixel = tuple[int, int]
N4 = ((1, 0), (-1, 0), (0, 1), (0, -1))
D6 = (
    (1, 0, 0), (-1, 0, 0),
    (0, 1, 0), (0, -1, 0),
    (0, 0, 1), (0, 0, -1),
)


def add(a: Vec3, b: Vec3) -> Vec3:
    return tuple(a[i] + b[i] for i in range(3))  # type: ignore[return-value]


def sub(a: Vec3, b: Vec3) -> Vec3:
    return tuple(a[i] - b[i] for i in range(3))  # type: ignore[return-value]


def cross(a: Vec3, b: Vec3) -> Vec3:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def nested_line(first: Vec3, second: Vec3) -> tuple[Vec3, ...]:
    """The current Java A/AB/ABC line, including its lexicographic tie choice."""
    start, end = (first, second) if first <= second else (second, first)
    delta = sub(end, start)
    distance = tuple(abs(value) for value in delta)
    major, middle, minor = sorted(range(3), key=lambda axis: (-distance[axis], axis))
    steps = distance[major]
    if steps == 0:
        return (start,)
    signs = tuple((value > 0) - (value < 0) for value in delta)
    result = []
    for i in range(steps + 1):
        j = (i * distance[middle] + steps // 2) // steps
        middle_steps = max(1, distance[middle])
        k = (j * distance[minor] + middle_steps // 2) // middle_steps
        progress = [0, 0, 0]
        progress[major], progress[middle], progress[minor] = i, j, k
        result.append(tuple(start[a] + signs[a] * progress[a] for a in range(3)))
    if first != start:
        result.reverse()
    return tuple(result)


@dataclass(frozen=True)
class FaceModel:
    vertices: tuple[Vec3, Vec3, Vec3, Vec3]
    normal: Vec3
    height_axis: int
    plane_axes: tuple[int, int]
    raw: frozenset[Vec3]
    owned: frozenset[Vec3]
    domain: frozenset[Pixel]
    preferred: dict[Pixel, frozenset[int]]
    owned_columns: dict[Pixel, frozenset[int]]
    boundary: frozenset[Pixel]
    interpolation_radius: int

    def project(self, point: Vec3) -> Pixel:
        return point[self.plane_axes[0]], point[self.plane_axes[1]]

    def restore(self, pixel: Pixel, height: int) -> Vec3:
        value = [0, 0, 0]
        value[self.plane_axes[0]], value[self.plane_axes[1]] = pixel
        value[self.height_axis] = height
        return tuple(value)  # type: ignore[return-value]

    def real_height(self, pixel: Pixel) -> Fraction:
        origin = self.vertices[0]
        numerator = self.normal[self.height_axis] * origin[self.height_axis]
        for index, axis in enumerate(self.plane_axes):
            numerator -= self.normal[axis] * (pixel[index] - origin[axis])
        return Fraction(numerator, self.normal[self.height_axis])


@dataclass(frozen=True)
class FaceMetrics:
    blocks: int
    columns: int
    projected_missing: int
    owned_missing: int
    maximum_thickness: int
    maximum_interior_thickness: int
    gap_columns: int
    air_leak: bool
    six_components: int
    support: int
    largest_support_component: int
    longest_extra_contour: int
    basin_cells: int
    strict_extrema: int


@dataclass(frozen=True)
class FaceResult:
    blocks: frozenset[Vec3]
    logical: frozenset[Vec3]
    support: frozenset[Vec3]
    model: FaceModel
    metrics: FaceMetrics
    method: str


def _inside_convex(point: Pixel, polygon: tuple[Pixel, ...]) -> bool:
    signs = []
    for first, second in zip(polygon, polygon[1:] + polygon[:1]):
        value = ((second[0] - first[0]) * (point[1] - first[1])
                 - (second[1] - first[1]) * (point[0] - first[0]))
        if value:
            signs.append(1 if value > 0 else -1)
    return not signs or min(signs) == max(signs)


def _bridge_diagonal_contacts(domain: set[Pixel], polygon: tuple[Pixel, ...]) -> set[Pixel]:
    """Make the immutable D8 shadow D4-connected with inward one-step bridges."""
    snapshot = set(domain)
    result = set(snapshot)
    scale = len(polygon)
    center_u = sum(point[0] for point in polygon)
    center_v = sum(point[1] for point in polygon)

    def center_distance(point: Pixel) -> int:
        return ((scale * point[0] - center_u) ** 2
                + (scale * point[1] - center_v) ** 2)

    for pixel in sorted(snapshot):
        for du, dv in ((1, 1), (1, -1)):
            diagonal = pixel[0] + du, pixel[1] + dv
            if diagonal not in snapshot:
                continue
            first = pixel[0] + du, pixel[1]
            second = pixel[0], pixel[1] + dv
            if first in snapshot or second in snapshot:
                continue
            first_distance = center_distance(first)
            second_distance = center_distance(second)
            if first_distance <= second_distance:
                result.add(first)
            if second_distance <= first_distance:
                result.add(second)
    return result


def _four_connected(domain: set[Pixel]) -> bool:
    if not domain:
        return True
    unseen = set(domain)
    queue = [unseen.pop()]
    while queue:
        pixel = queue.pop()
        for du, dv in N4:
            neighbor = pixel[0] + du, pixel[1] + dv
            if neighbor in unseen:
                unseen.remove(neighbor)
                queue.append(neighbor)
    return not unseen


def _has_no_projected_hole(domain: set[Pixel]) -> bool:
    if not domain:
        return True
    minimum_u = min(pixel[0] for pixel in domain) - 1
    maximum_u = max(pixel[0] for pixel in domain) + 1
    minimum_v = min(pixel[1] for pixel in domain) - 1
    maximum_v = max(pixel[1] for pixel in domain) + 1
    exterior = {(minimum_u, minimum_v)}
    queue = deque(exterior)
    while queue:
        pixel = queue.popleft()
        for du, dv in N4:
            neighbor = pixel[0] + du, pixel[1] + dv
            if not (minimum_u <= neighbor[0] <= maximum_u
                    and minimum_v <= neighbor[1] <= maximum_v):
                continue
            if neighbor in domain or neighbor in exterior:
                continue
            exterior.add(neighbor)
            queue.append(neighbor)
    return all(
        (u, v) in domain or (u, v) in exterior
        for u in range(minimum_u, maximum_u + 1)
        for v in range(minimum_v, maximum_v + 1)
    )


def _complete_projected_domain(domain: set[Pixel]) -> set[Pixel]:
    """Define the domain once by adding bounded complement cells, never voxels post hoc."""
    if not domain:
        return set()
    minimum_u = min(pixel[0] for pixel in domain) - 1
    maximum_u = max(pixel[0] for pixel in domain) + 1
    minimum_v = min(pixel[1] for pixel in domain) - 1
    maximum_v = max(pixel[1] for pixel in domain) + 1
    exterior = {(minimum_u, minimum_v)}
    queue = deque(exterior)
    while queue:
        pixel = queue.popleft()
        for du, dv in N4:
            neighbor = pixel[0] + du, pixel[1] + dv
            if not (minimum_u <= neighbor[0] <= maximum_u
                    and minimum_v <= neighbor[1] <= maximum_v):
                continue
            if neighbor in domain or neighbor in exterior:
                continue
            exterior.add(neighbor)
            queue.append(neighbor)
    return {
        (u, v)
        for u in range(minimum_u + 1, maximum_u)
        for v in range(minimum_v + 1, maximum_v)
        if (u, v) not in exterior
    }


def _column_map(points: set[Vec3] | frozenset[Vec3], model: FaceModel | None = None,
                axes: tuple[int, int] | None = None, height_axis: int | None = None):
    if model is not None:
        axes, height_axis = model.plane_axes, model.height_axis
    assert axes is not None and height_axis is not None
    result: dict[Pixel, set[int]] = defaultdict(set)
    for point in points:
        result[(point[axes[0]], point[axes[1]])].add(point[height_axis])
    return result


def _phase_sample(heights: set[int], real: Fraction) -> Fraction:
    return sum((Fraction(height) - real for height in heights), Fraction()) / len(heights)


def _interpolated_preference(
    pixel: Pixel,
    known: dict[Pixel, set[int]],
    real_height,
    radius_limit: int,
) -> tuple[frozenset[int], int]:
    """Interpolate only from the immutable raw snapshot, never from new fills."""
    rays = []
    for du, dv in N4:
        for radius in range(1, radius_limit + 1):
            sample = pixel[0] + du * radius, pixel[1] + dv * radius
            if sample in known:
                rays.append(((du, dv), radius, _phase_sample(known[sample], real_height(sample))))
                break
    by_direction = {direction: (radius, phase) for direction, radius, phase in rays}
    estimates: list[Fraction] = []
    for negative, positive in (((-1, 0), (1, 0)), ((0, -1), (0, 1))):
        if negative in by_direction and positive in by_direction:
            dn, en = by_direction[negative]
            dp, ep = by_direction[positive]
            estimates.append((dp * en + dn * ep) / (dn + dp))
    if estimates:
        phase = sum(estimates, Fraction()) / len(estimates)
    elif rays:
        weights = [(Fraction(1, radius), value) for _, radius, value in rays]
        phase = sum((weight * value for weight, value in weights), Fraction()) / sum(
            (weight for weight, _ in weights), Fraction()
        )
    else:
        phase = Fraction()
    target = real_height(pixel) + phase
    low = floor(target)
    choices = {low} if target.denominator == 1 else {low, low + 1}
    return frozenset(choices), max((radius for _, radius, _ in rays), default=0)


def build_model(vertices: tuple[Vec3, Vec3, Vec3, Vec3]) -> FaceModel:
    origin, p10, p11, p01 = vertices
    edge_u, edge_v = sub(p10, origin), sub(p01, origin)
    if add(p10, edge_v) != p11:
        raise ValueError("vertices must be one cyclic parallelogram")
    normal = cross(edge_u, edge_v)
    if normal == (0, 0, 0):
        raise ValueError("degenerate parallelogram")
    height_axis = max(range(3), key=lambda axis: (abs(normal[axis]), -axis))
    plane_axes = tuple(axis for axis in range(3) if axis != height_axis)
    project = lambda point: (point[plane_axes[0]], point[plane_axes[1]])

    line_u = tuple(sub(point, origin) for point in nested_line(origin, p10))
    line_v = tuple(sub(point, origin) for point in nested_line(origin, p01))
    raw = {add(origin, add(u, v)) for u in line_u for v in line_v}
    owned = set().union(*(
        nested_line(vertices[index], vertices[(index + 1) % 4])
        for index in range(4)
    ))
    raw_columns = _column_map(raw, axes=plane_axes, height_axis=height_axis)
    owned_columns = _column_map(owned, axes=plane_axes, height_axis=height_axis)

    polygon = tuple(project(point) for point in vertices)
    u_range = range(min(p[0] for p in polygon), max(p[0] for p in polygon) + 1)
    v_range = range(min(p[1] for p in polygon), max(p[1] for p in polygon) + 1)
    domain = {
        pixel for pixel in product(u_range, v_range)
        if _inside_convex(pixel, polygon)
    } | set(raw_columns) | set(owned_columns)
    domain = _complete_projected_domain(_bridge_diagonal_contacts(domain, polygon))
    if not _four_connected(domain):
        raise RuntimeError("one-shot projected domain bridge did not connect the face")
    if not _has_no_projected_hole(domain):
        raise RuntimeError("projected domain contains a bounded hole")

    temporary = FaceModel(
        vertices, normal, height_axis, plane_axes, frozenset(raw), frozenset(owned),
        frozenset(domain), {}, {}, frozenset(), 0,
    )
    radius_limit = max(len(u_range), len(v_range)) + 2
    preferred: dict[Pixel, frozenset[int]] = {}
    maximum_radius = 0
    for pixel in sorted(domain):
        if pixel in raw_columns:
            preferred[pixel] = frozenset(raw_columns[pixel])
        else:
            preferred[pixel], radius = _interpolated_preference(
                pixel, raw_columns, temporary.real_height, radius_limit
            )
            maximum_radius = max(maximum_radius, radius)
    boundary = {
        pixel for pixel in domain
        if any((pixel[0] + du, pixel[1] + dv) not in domain for du, dv in N4)
    }
    return FaceModel(
        vertices, normal, height_axis, plane_axes,
        frozenset(raw), frozenset(owned), frozenset(domain), preferred,
        {pixel: frozenset(values) for pixel, values in owned_columns.items()},
        frozenset(boundary), maximum_radius,
    )


def _candidate_heights(model: FaceModel, band: int) -> dict[Pixel, set[int]]:
    result = {}
    for pixel in model.domain:
        real = model.real_height(pixel)
        result[pixel] = (
            set(range(floor(real) - band, ceil(real) + band + 1))
            | set(model.preferred[pixel])
            | set(model.owned_columns.get(pixel, ()))
        )
    return result


def minimum_separator(model: FaceModel, band: int = 2) -> frozenset[Vec3]:
    """Unconstrained minimum vertex cut; callers must reject thickness violations."""
    candidates = _candidate_heights(model, band)
    ranges = {
        pixel: range(min(values) - 1, max(values) + 2)
        for pixel, values in candidates.items()
    }
    fixed = {
        (pixel, height)
        for pixel, heights in model.owned_columns.items()
        for height in heights
    }
    cells = {(pixel, height) for pixel, values in ranges.items() for height in values}
    selectable_cost = {}
    errors = {}
    for pixel in model.domain:
        real = model.real_height(pixel)
        phase_target = sum(model.preferred[pixel], Fraction()) / len(model.preferred[pixel])
        for height in candidates[pixel]:
            plane_error = abs(Fraction(height) - real)
            phase_error = abs(Fraction(height) - phase_target)
            errors[pixel, height] = int(1000 * (plane_error + phase_error))
    maximum_error = max(errors.values(), default=0) + 1
    count = max(1, len(model.domain))
    preferred_weight = count * maximum_error + 1
    voxel_weight = count * (preferred_weight + maximum_error) + 1
    for key, error in errors.items():
        pixel, height = key
        selectable_cost[key] = (
            voxel_weight
            + (0 if height in model.preferred[pixel] else preferred_weight)
            + error
        )
    infinity = sum(selectable_cost.values()) + 1

    source, sink = ("source",), ("sink",)
    graph = nx.DiGraph()
    enter = lambda key: ("in", key)
    leave = lambda key: ("out", key)
    air = cells - fixed
    for key in sorted(air):
        pixel, height = key
        graph.add_edge(enter(key), leave(key), capacity=selectable_cost.get(key, infinity))
        values = ranges[pixel]
        if height == values.start:
            graph.add_edge(source, enter(key), capacity=infinity)
        if height == values.stop - 1:
            graph.add_edge(leave(key), sink, capacity=infinity)
        above = pixel, height + 1
        if above in air:
            graph.add_edge(leave(key), enter(above), capacity=infinity)
            graph.add_edge(leave(above), enter(key), capacity=infinity)
        for du, dv in ((1, 0), (0, 1)):
            neighbor_pixel = pixel[0] + du, pixel[1] + dv
            if neighbor_pixel not in model.domain:
                continue
            neighbor = neighbor_pixel, height
            if neighbor in air:
                graph.add_edge(leave(key), enter(neighbor), capacity=infinity)
                graph.add_edge(leave(neighbor), enter(key), capacity=infinity)
            elif neighbor not in cells:
                neighbor_range = ranges[neighbor_pixel]
                if height < neighbor_range.start:
                    graph.add_edge(source, enter(key), capacity=infinity)
                elif height >= neighbor_range.stop:
                    graph.add_edge(leave(key), sink, capacity=infinity)
    _, (lower, upper) = nx.minimum_cut(graph, source, sink)
    selected = {
        model.restore(pixel, height)
        for key in air
        if enter(key) in lower and leave(key) in upper
        for pixel, height in (key,)
    }
    return frozenset(selected | set(model.owned))


def _graph_fallback(model: FaceModel, upper: bool) -> frozenset[Vec3]:
    points = set(model.owned)
    for pixel in model.domain:
        real = model.real_height(pixel)
        height = ceil(real) if upper else floor(real)
        values = {height} | set(model.owned_columns.get(pixel, ()))
        points.update(model.restore(pixel, value) for value in range(min(values), max(values) + 1))
    return frozenset(points)


def _components(points: set[Vec3] | frozenset[Vec3]) -> int:
    unseen = set(points)
    result = 0
    while unseen:
        result += 1
        queue = [unseen.pop()]
        while queue:
            point = queue.pop()
            for offset in D6:
                neighbor = add(point, offset)
                if neighbor in unseen:
                    unseen.remove(neighbor)
                    queue.append(neighbor)
    return result


def air_leaks(model: FaceModel, points: set[Vec3] | frozenset[Vec3]) -> bool:
    columns = _column_map(points, model=model)
    heights = [height for values in columns.values() for height in values]
    if not heights:
        return True
    low, high = min(heights) - 2, max(heights) + 2
    solid = {(pixel, height) for pixel, values in columns.items() for height in values}
    air = {(pixel, height) for pixel in model.domain for height in range(low, high + 1)} - solid
    queue = deque((pixel, low) for pixel in model.domain if (pixel, low) in air)
    seen = set(queue)
    while queue:
        pixel, height = queue.popleft()
        if height == high:
            return True
        neighbors = [(pixel, height - 1), (pixel, height + 1)]
        neighbors.extend(((pixel[0] + du, pixel[1] + dv), height) for du, dv in N4)
        for neighbor in neighbors:
            if neighbor in air and neighbor not in seen:
                seen.add(neighbor)
                queue.append(neighbor)
    return False


def _largest_component(cells: set[Pixel]) -> int:
    unseen = set(cells)
    largest = 0
    while unseen:
        queue = [unseen.pop()]
        size = 0
        while queue:
            pixel = queue.pop()
            size += 1
            for du, dv in N4:
                neighbor = pixel[0] + du, pixel[1] + dv
                if neighbor in unseen:
                    unseen.remove(neighbor)
                    queue.append(neighbor)
        largest = max(largest, size)
    return largest


def _logical_height(model: FaceModel, pixel: Pixel, heights: set[int]) -> int:
    preferred = heights & set(model.preferred[pixel])
    phase_target = sum(model.preferred[pixel], Fraction()) / len(model.preferred[pixel])
    choices = preferred or heights
    return min(choices, key=lambda height: (
        abs(Fraction(height) - phase_target),
        abs(Fraction(height) - model.real_height(pixel)),
        height,
    ))


def _strict_extrema_count(terrain: dict[Pixel, int]) -> int:
    result = 0
    for pixel, height in terrain.items():
        neighbors = [(pixel[0] + du, pixel[1] + dv) for du, dv in N4]
        if all(neighbor in terrain for neighbor in neighbors):
            values = [terrain[neighbor] for neighbor in neighbors]
            result += all(height < value for value in values) or all(
                height > value for value in values
            )
    return result


def _enclosed_basin_cells(terrain: dict[Pixel, int]) -> int:
    if not terrain:
        return 0
    escape: dict[Pixel, int] = {}
    open_cells: list[tuple[int, Pixel]] = []
    for pixel, height in terrain.items():
        if any((pixel[0] + du, pixel[1] + dv) not in terrain for du, dv in N4):
            escape[pixel] = height
            heappush(open_cells, (height, pixel))
    while open_cells:
        level, pixel = heappop(open_cells)
        if escape.get(pixel) != level:
            continue
        for du, dv in N4:
            neighbor = pixel[0] + du, pixel[1] + dv
            if neighbor not in terrain:
                continue
            candidate = max(level, terrain[neighbor])
            if candidate < escape.get(neighbor, 1 << 62):
                escape[neighbor] = candidate
                heappush(open_cells, (candidate, neighbor))
    return sum(escape.get(pixel, 1 << 62) > height for pixel, height in terrain.items())


def metrics(model: FaceModel, points: set[Vec3] | frozenset[Vec3]) -> FaceMetrics:
    columns = _column_map(points, model=model)
    logical_height = {
        pixel: _logical_height(model, pixel, values)
        for pixel, values in columns.items() if pixel in model.domain
    }
    logical = {model.restore(pixel, height) for pixel, height in logical_height.items()}
    support_points = set(points) - logical - set(model.owned)
    support_cells = {
        model.project(point) for point in support_points
        if model.project(point) in model.domain
    }
    extra_edges: set[tuple[Pixel, Pixel]] = set()
    for pixel, height in logical_height.items():
        for du, dv in ((1, 0), (0, 1)):
            neighbor = pixel[0] + du, pixel[1] + dv
            if neighbor not in logical_height:
                continue
            edge = tuple(sorted((pixel, neighbor)))
            logical_step = logical_height[pixel] != logical_height[neighbor]
            lower_step = min(columns[pixel]) != min(columns[neighbor])
            upper_step = max(columns[pixel]) != max(columns[neighbor])
            if logical_step != lower_step or logical_step != upper_step:
                extra_edges.add(edge)  # type: ignore[arg-type]
    upper = {pixel: max(values) for pixel, values in columns.items() if pixel in model.domain}
    lower = {pixel: min(values) for pixel, values in columns.items() if pixel in model.domain}
    extrema = (
        _strict_extrema_count(logical_height)
        + _strict_extrema_count(upper)
        + _strict_extrema_count(lower)
    )
    basin_cells = _enclosed_basin_cells(upper) + _enclosed_basin_cells({
        pixel: -height for pixel, height in lower.items()
    })
    edge_cells = {pixel for edge in extra_edges for pixel in edge}
    interior = set(model.domain) - set(model.boundary) - set(model.owned_columns)
    thickness = {pixel: len(columns.get(pixel, ())) for pixel in model.domain}
    return FaceMetrics(
        blocks=len(points),
        columns=len(columns),
        projected_missing=len(set(model.domain) - set(columns)),
        owned_missing=len(set(model.owned) - set(points)),
        maximum_thickness=max(thickness.values(), default=0),
        maximum_interior_thickness=max((thickness[p] for p in interior), default=0),
        gap_columns=sum(
            bool(values) and max(values) - min(values) + 1 != len(values)
            for pixel, values in columns.items() if pixel in model.domain
        ),
        air_leak=air_leaks(model, points),
        six_components=_components(points),
        support=len(support_points),
        largest_support_component=_largest_component(support_cells & interior),
        longest_extra_contour=_largest_component(edge_cells),
        basin_cells=basin_cells,
        strict_extrema=extrema,
    )


def _hard_valid(metric: FaceMetrics, interior_cap: int = 2) -> bool:
    return (
        metric.projected_missing == 0
        and metric.owned_missing == 0
        and metric.maximum_interior_thickness <= interior_cap
        and metric.gap_columns == 0
        and not metric.air_leak
    )


def generate(vertices: tuple[Vec3, Vec3, Vec3, Vec3], band: int = 2) -> FaceResult:
    model = build_model(vertices)
    candidates = [
        ("translated-cut", minimum_separator(model, band)),
        ("translated-filled", frozenset(
            model.restore(pixel, height)
            for pixel, heights in model.preferred.items()
            for height in heights
        ) | model.owned),
        ("floor-fallback", _graph_fallback(model, False)),
        ("ceil-fallback", _graph_fallback(model, True)),
    ]
    method_priority = {
        "translated-filled": 0,
        "translated-cut": 1,
        "floor-fallback": 2,
        "ceil-fallback": 3,
    }
    accepted = []
    for method, points in candidates:
        value = metrics(model, points)
        if _hard_valid(value):
            accepted.append((
                (
                    value.basin_cells,
                    value.strict_extrema,
                    value.largest_support_component,
                    value.longest_extra_contour,
                    value.support,
                    value.blocks,
                    method_priority[method],
                ),
                method,
                points,
                value,
            ))
    if not accepted:
        # The floor graph is the total-function safety net.  It keeps one
        # 1-Lipschitz height per domain column and only adds owned column spans.
        points = _graph_fallback(model, False)
        accepted.append(((1 << 60,), "floor-emergency", points, metrics(model, points)))
    _, method, points, value = min(accepted)
    columns = _column_map(points, model=model)
    logical = frozenset(
        model.restore(pixel, _logical_height(model, pixel, heights))
        for pixel, heights in columns.items() if pixel in model.domain
    )
    return FaceResult(points, logical, points - logical - model.owned, model, value, method)
