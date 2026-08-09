from __future__ import annotations

import argparse
import math
from collections import deque
from collections.abc import Callable, Iterable, Sequence

from planar_faces import (
    EPSILON,
    Point2,
    Projection,
    Vec3,
    Voxel,
    add,
    containing,
    contains,
    dominant_axis_face,
    dot,
    face_normal,
    floor_stable,
    line_voxels,
    normalize,
    scale,
    subtract,
)

NEIGHBORS: tuple[Voxel, ...] = (
    (1, 0, 0),
    (-1, 0, 0),
    (0, 1, 0),
    (0, -1, 0),
    (0, 0, 1),
    (0, 0, -1),
)


def add_voxel(a: Voxel, b: Voxel) -> Voxel:
    return a[0] + b[0], a[1] + b[1], a[2] + b[2]


def center_of(voxel: Voxel) -> Vec3:
    return voxel[0] + 0.5, voxel[1] + 0.5, voxel[2] + 0.5


def average(points: Sequence[Vec3]) -> Vec3:
    count = len(points)
    return (
        sum(point[0] for point in points) / count,
        sum(point[1] for point in points) / count,
        sum(point[2] for point in points) / count,
    )


def cross(a: Vec3, b: Vec3) -> Vec3:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def plane_axes(normal: Vec3) -> tuple[Vec3, Vec3, Vec3]:
    safe_normal = normalize(normal)
    reference = (0.0, 1.0, 0.0) if abs(safe_normal[1]) < 0.9 else (1.0, 0.0, 0.0)
    axis_u = normalize(cross(reference, safe_normal))
    axis_v = normalize(cross(safe_normal, axis_u))
    return safe_normal, axis_u, axis_v


def voxel_bounds(points: Iterable[Vec3]) -> tuple[range, range, range]:
    values = tuple(points)
    return tuple(
        range(
            floor_stable(min(point[axis] for point in values)),
            floor_stable(max(point[axis] for point in values)) + 1,
        )
        for axis in range(3)
    )  # type: ignore[return-value]


def voxel_set_bounds(voxels: Iterable[Voxel], padding: int = 0) -> tuple[range, range, range]:
    values = tuple(voxels)
    return tuple(
        range(
            min(voxel[axis] for voxel in values) - padding,
            max(voxel[axis] for voxel in values) + padding + 1,
        )
        for axis in range(3)
    )  # type: ignore[return-value]


def enumerate_inside(
    bounds: tuple[range, range, range],
    inside: Callable[[Voxel], bool],
    max_voxels: int | None = None,
) -> set[Voxel]:
    result: set[Voxel] = set()
    for x in bounds[0]:
        for y in bounds[1]:
            for z in bounds[2]:
                voxel = x, y, z
                if inside(voxel):
                    result.add(voxel)
                    if max_voxels is not None and len(result) >= max_voxels:
                        return result
    return result


def six_neighbor_boundary(solid: set[Voxel]) -> set[Voxel]:
    return {
        voxel
        for voxel in solid
        if any(add_voxel(voxel, neighbor) not in solid for neighbor in NEIGHBORS)
    }


def frustum_geometry(
    base_center: Vec3,
    normal: Vec3,
    height: float,
    radius: float,
    scale_x: float = 1.0,
    scale_z: float = 1.0,
    top_scale: float = 1.0,
    top_offset: Point2 = (0.0, 0.0),
) -> tuple[tuple[range, range, range], Callable[[Voxel], bool]]:
    safe_normal, axis_u, axis_v = plane_axes(normal)
    top_center = add(
        add(base_center, scale(safe_normal, height)),
        add(scale(axis_u, top_offset[0]), scale(axis_v, top_offset[1])),
    )
    bottom_radius = max(0.0, radius) + 0.25
    top_radius = max(0.0, radius * max(0.0, top_scale)) + 0.25

    def endpoint_extent(section_radius: float, axis: int) -> float:
        return (
            abs(axis_u[axis]) * section_radius * scale_x
            + abs(axis_v[axis]) * section_radius * scale_z
        )

    ranges: list[range] = []
    for axis in range(3):
        minimum = min(
            base_center[axis] - endpoint_extent(bottom_radius, axis),
            top_center[axis] - endpoint_extent(top_radius, axis),
        )
        maximum = max(
            base_center[axis] + endpoint_extent(bottom_radius, axis),
            top_center[axis] + endpoint_extent(top_radius, axis),
        )
        ranges.append(
            range(
                math.ceil(minimum - 0.5 - EPSILON),
                math.floor(maximum - 0.5 + EPSILON) + 1,
            )
        )

    def inside(voxel: Voxel) -> bool:
        point = center_of(voxel)
        from_base = subtract(point, base_center)
        ratio = dot(from_base, safe_normal) / height
        if ratio < -EPSILON or ratio > 1.0 + EPSILON:
            return False
        center = add(
            add(base_center, scale(safe_normal, height * ratio)),
            add(scale(axis_u, top_offset[0] * ratio), scale(axis_v, top_offset[1] * ratio)),
        )
        local = subtract(point, center)
        section_radius = max(0.0, radius * (1.0 + (top_scale - 1.0) * ratio)) + 0.25
        local_u = dot(local, axis_u) / scale_x
        local_v = dot(local, axis_v) / scale_z
        return local_u * local_u + local_v * local_v <= section_radius * section_radius + EPSILON

    if abs(height) <= EPSILON:
        return tuple(ranges), lambda voxel: False  # type: ignore[return-value]
    return tuple(ranges), inside  # type: ignore[return-value]


def frustum_solid(
    base_center: Vec3,
    normal: Vec3,
    height: float,
    radius: float,
    scale_x: float = 1.0,
    scale_z: float = 1.0,
    top_scale: float = 1.0,
    top_offset: Point2 = (0.0, 0.0),
) -> set[Voxel]:
    bounds, inside = frustum_geometry(
        base_center, normal, height, radius, scale_x, scale_z, top_scale, top_offset
    )
    return enumerate_inside(bounds, inside)


def current_layered_cylinder(
    base_center: Vec3,
    normal: Vec3,
    height: int,
    radius: float,
) -> set[Voxel]:
    safe_normal, axis_u, axis_v = plane_axes(normal)
    result: set[Voxel] = set()
    extent = math.ceil(radius + 0.25 + math.sqrt(3.0) * 0.5)
    for layer in range(abs(height) + 1):
        center = add(base_center, scale(safe_normal, math.copysign(layer, height)))
        for local_u in range(-extent, extent + 1):
            for local_v in range(-extent, extent + 1):
                sample = add(center, add(scale(axis_u, local_u), scale(axis_v, local_v)))
                voxel = containing(sample)
                offset = subtract(center_of(voxel), center)
                x = dot(offset, axis_u)
                z = dot(offset, axis_v)
                if math.hypot(x, z) <= radius + 0.25 + EPSILON:
                    result.add(voxel)
    return result


def quad_prism_faces(base: Sequence[Vec3], extrusion: Vec3) -> tuple[tuple[Vec3, ...], ...]:
    if len(base) != 4:
        raise ValueError("quad prism requires four base vertices")
    top = tuple(add(vertex, extrusion) for vertex in base)
    sides = tuple(
        (base[index], base[(index + 1) % 4], top[(index + 1) % 4], top[index])
        for index in range(4)
    )
    return tuple(base), top, *sides


def quad_prism_shell(base: Sequence[Vec3], extrusion: Vec3) -> set[Voxel]:
    return set().union(*(dominant_axis_face(face) for face in quad_prism_faces(base, extrusion)))


def shell_flood_solid(shell: set[Voxel]) -> set[Voxel]:
    if not shell:
        return set()
    bounds = voxel_set_bounds(shell, 1)
    limits = tuple((axis_range.start, axis_range.stop) for axis_range in bounds)
    start = (bounds[0].start, bounds[1].start, bounds[2].start)
    exterior = {start}
    queue = deque((start,))
    while queue:
        voxel = queue.popleft()
        for neighbor in NEIGHBORS:
            candidate = add_voxel(voxel, neighbor)
            if candidate in exterior or candidate in shell:
                continue
            if any(candidate[axis] < limits[axis][0] or candidate[axis] >= limits[axis][1] for axis in range(3)):
                continue
            exterior.add(candidate)
            queue.append(candidate)
    return {
        (x, y, z)
        for x in bounds[0]
        for y in bounds[1]
        for z in bounds[2]
        if (x, y, z) not in exterior
    }


def quad_halfspace_geometry(
    base: Sequence[Vec3], extrusion: Vec3
) -> tuple[tuple[range, range, range], Callable[[Voxel], bool]]:
    faces = quad_prism_faces(base, extrusion)
    vertices = tuple(base) + tuple(add(vertex, extrusion) for vertex in base)
    center = average(vertices)
    planes: list[tuple[Vec3, Vec3, float]] = []
    for face in faces:
        normal = normalize(face_normal(face))
        origin = face[0]
        center_distance = dot(normal, subtract(center, origin))
        if abs(center_distance) <= EPSILON:
            return voxel_bounds(vertices), lambda voxel: False
        planes.append((origin, normal, math.copysign(1.0, center_distance)))

    def inside(voxel: Voxel) -> bool:
        point = center_of(voxel)
        return all(
            sign * dot(normal, subtract(point, origin)) >= -EPSILON
            for origin, normal, sign in planes
        )

    return voxel_bounds(vertices), inside


def quad_halfspace_solid(
    base: Sequence[Vec3], extrusion: Vec3, max_voxels: int | None = None
) -> set[Voxel]:
    bounds, inside = quad_halfspace_geometry(base, extrusion)
    return enumerate_inside(bounds, inside, max_voxels)


def quad_halfspace_hollow(
    base: Sequence[Vec3], extrusion: Vec3, max_voxels: int | None = None
) -> set[Voxel]:
    solid = quad_halfspace_solid(base, extrusion)
    boundary = six_neighbor_boundary(solid)
    if max_voxels is None:
        return boundary
    return set(sorted(boundary)[:max_voxels])


def quad_halfspace_outline(
    base: Sequence[Vec3], extrusion: Vec3, max_voxels: int | None = None
) -> set[Voxel]:
    """Rasterize the twelve mathematical box edges onto its canonical surface."""
    _, inside = quad_halfspace_geometry(base, extrusion)
    top = tuple(add(vertex, extrusion) for vertex in base)
    edges = tuple(
        (vertices[index], vertices[(index + 1) % len(vertices)])
        for vertices in (tuple(base), top)
        for index in range(len(vertices))
    ) + tuple(zip(base, top))
    result: set[Voxel] = set()
    for start, end in edges:
        previous: Voxel | None = None
        delta = subtract(end, start)
        steps = max(1, math.ceil(max(abs(delta[0]), abs(delta[1]), abs(delta[2]))))
        for step in range(steps + 1):
            point = add(start, scale(delta, step / steps))
            candidate = nearest_surface_voxel(point, previous, inside)
            if candidate is None:
                continue
            result.add(candidate)
            previous = candidate
            if max_voxels is not None and len(result) >= max_voxels:
                return result
    return result


def nearest_surface_voxel(
    point: Vec3, previous: Voxel | None, inside: Callable[[Voxel], bool]
) -> Voxel | None:
    origin = containing(point)
    for radius in (1, 2):
        candidates: list[Voxel] = []
        for x in range(origin[0] - radius, origin[0] + radius + 1):
            for y in range(origin[1] - radius, origin[1] + radius + 1):
                for z in range(origin[2] - radius, origin[2] + radius + 1):
                    voxel = x, y, z
                    if inside(voxel) and any(not inside(add_voxel(voxel, neighbor)) for neighbor in NEIGHBORS):
                        candidates.append(voxel)
        if candidates:
            return min(candidates, key=lambda voxel: surface_candidate_key(voxel, point, previous))
    return None


def surface_candidate_key(voxel: Voxel, point: Vec3, previous: Voxel | None) -> tuple[float, ...]:
    center = center_of(voxel)
    adjacent = previous is None or max(abs(voxel[axis] - previous[axis]) for axis in range(3)) <= 1
    previous_distance = 0.0 if previous is None else sum(
        (voxel[axis] - previous[axis]) ** 2 for axis in range(3)
    )
    point_distance = sum((center[axis] - point[axis]) ** 2 for axis in range(3))
    return (0.0 if adjacent else 1.0, point_distance, previous_distance, *voxel)


class ProjectedPolygon:
    def __init__(self, vertices: Sequence[Vec3]) -> None:
        self.origin = vertices[0]
        self.normal = normalize(face_normal(vertices))
        self.projection = Projection.for_normal(self.normal)
        self.polygon: tuple[Point2, ...] = tuple(self.projection.project_point(vertex) for vertex in vertices)

    def contains(self, point: Vec3) -> bool:
        return contains(self.polygon, self.projection.project_point(point))


def prism_geometry(
    base: Sequence[Vec3], extrusion: Vec3
) -> tuple[tuple[range, range, range], Callable[[Voxel], bool]]:
    polygon = ProjectedPolygon(base)
    normal_distance = dot(polygon.normal, extrusion)
    if abs(normal_distance) <= EPSILON:
        return voxel_bounds(base), lambda voxel: voxel in dominant_axis_face(base)
    top = tuple(add(vertex, extrusion) for vertex in base)

    def inside(voxel: Voxel) -> bool:
        point = center_of(voxel)
        ratio = dot(polygon.normal, subtract(point, polygon.origin)) / normal_distance
        if ratio < -EPSILON or ratio > 1.0 + EPSILON:
            return False
        return polygon.contains(subtract(point, scale(extrusion, ratio)))

    return voxel_bounds(tuple(base) + top), inside


def prism_solid(base: Sequence[Vec3], extrusion: Vec3, max_voxels: int | None = None) -> set[Voxel]:
    bounds, inside = prism_geometry(base, extrusion)
    return enumerate_inside(bounds, inside, max_voxels)


def prism_hollow(base: Sequence[Vec3], extrusion: Vec3, max_voxels: int | None = None) -> set[Voxel]:
    bounds, inside = prism_geometry(base, extrusion)
    result: set[Voxel] = set()
    for x in bounds[0]:
        for y in bounds[1]:
            for z in bounds[2]:
                voxel = x, y, z
                if inside(voxel) and any(not inside(add_voxel(voxel, neighbor)) for neighbor in NEIGHBORS):
                    result.add(voxel)
                    if max_voxels is not None and len(result) >= max_voxels:
                        return result
    return result


def current_layered_prism(base: Sequence[Vec3], extrusion: Vec3) -> set[Voxel]:
    base_face = dominant_axis_face(base)
    offsets = line_voxels((0.0, 0.0, 0.0), extrusion)
    return {add_voxel(voxel, offset) for voxel in base_face for offset in offsets}


def pyramid_geometry(
    base: Sequence[Vec3], extrusion: Vec3
) -> tuple[tuple[range, range, range], Callable[[Voxel], bool], Voxel]:
    polygon = ProjectedPolygon(base)
    base_center = average(base)
    height = dot(extrusion, polygon.normal)
    apex = add(base_center, scale(polygon.normal, height))
    apex_voxel = containing(apex)
    if abs(height) <= EPSILON:
        return voxel_bounds(base), lambda voxel: voxel in dominant_axis_face(base), apex_voxel

    def inside(voxel: Voxel) -> bool:
        if voxel == apex_voxel:
            return True
        point = center_of(voxel)
        ratio = dot(polygon.normal, subtract(point, polygon.origin)) / height
        if ratio < -EPSILON or ratio >= 1.0 - EPSILON:
            return False
        section_center = add(base_center, scale(polygon.normal, height * ratio))
        base_point = add(base_center, scale(subtract(point, section_center), 1.0 / (1.0 - ratio)))
        return polygon.contains(base_point)

    return voxel_bounds(tuple(base) + (apex,)), inside, apex_voxel


def pyramid_solid(base: Sequence[Vec3], extrusion: Vec3, max_voxels: int | None = None) -> set[Voxel]:
    bounds, inside, _ = pyramid_geometry(base, extrusion)
    return enumerate_inside(bounds, inside, max_voxels)


def pyramid_hollow(base: Sequence[Vec3], extrusion: Vec3, max_voxels: int | None = None) -> set[Voxel]:
    bounds, inside, _ = pyramid_geometry(base, extrusion)
    result: set[Voxel] = set()
    for x in bounds[0]:
        for y in bounds[1]:
            for z in bounds[2]:
                voxel = x, y, z
                if inside(voxel) and any(not inside(add_voxel(voxel, neighbor)) for neighbor in NEIGHBORS):
                    result.add(voxel)
                    if max_voxels is not None and len(result) >= max_voxels:
                        return result
    return result


def report() -> None:
    base = (
        (0.5, 0.5, 0.5),
        (-2.5, -2.5, -2.5),
        (-5.5, -5.5, 0.5),
        (-2.5, -2.5, 3.5),
    )
    extrusion = (-4.0, 4.0, 0.0)
    old = current_layered_prism(base, extrusion)
    solid = prism_solid(base, extrusion)
    old_surface = six_neighbor_boundary(old)
    surface = prism_hollow(base, extrusion)
    print(f"old solid={len(old)} surface={len(old_surface)} exposed_ratio={len(old_surface) / len(old):.3f}")
    print(f"new solid={len(solid)} surface={len(surface)} exposed_ratio={len(surface) / len(solid):.3f}")
    print(f"surface_equals_boundary={surface == six_neighbor_boundary(solid)}")
    print(f"old_surface_difference={len(old_surface ^ surface)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", action="store_true")
    arguments = parser.parse_args()
    if arguments.report:
        report()
