"""Canonical convex volume generated as contiguous integer column intervals."""
from math import ceil, floor

from closed_volumes import (
    EPSILON,
    NEIGHBORS,
    add,
    add_voxel,
    average,
    center_of,
    dot,
    normalize,
    quad_prism_faces,
    subtract,
    voxel_bounds,
)
from planar_faces import face_normal


def inward_planes(base, extrusion):
    faces = quad_prism_faces(base, extrusion)
    center = average(tuple(base) + tuple(add(vertex, extrusion) for vertex in base))
    result = []
    for face in faces:
        normal = normalize(face_normal(face))
        if dot(normal, subtract(center, face[0])) < 0:
            normal = tuple(-value for value in normal)
        result.append((face[0], normal))
    return tuple(result)


def column_solid(base, extrusion, limit=None):
    bounds = voxel_bounds(tuple(base) + tuple(add(vertex, extrusion) for vertex in base))
    planes = inward_planes(base, extrusion)
    axis = min(range(3), key=lambda candidate: len(bounds[(candidate + 1) % 3]) * len(bounds[(candidate + 2) % 3]))
    others = tuple(candidate for candidate in range(3) if candidate != axis)
    result = set()
    for first in bounds[others[0]]:
        for second in bounds[others[1]]:
            low, high, valid = bounds[axis].start, bounds[axis].stop - 1, True
            fixed = [0.0, 0.0, 0.0]
            fixed[others[0]], fixed[others[1]] = first + .5, second + .5
            for origin, normal in planes:
                rest = sum(normal[i] * (fixed[i] - origin[i]) for i in others)
                coefficient = normal[axis]
                if abs(coefficient) <= EPSILON:
                    valid &= rest >= -EPSILON
                elif coefficient > 0:
                    low = max(low, ceil(origin[axis] + (-EPSILON - rest) / coefficient - .5 - EPSILON))
                else:
                    high = min(high, floor(origin[axis] + (-EPSILON - rest) / coefficient - .5 + EPSILON))
            if not valid or low > high:
                continue
            for coordinate in range(low, high + 1):
                point = [0, 0, 0]; point[axis] = coordinate
                point[others[0]], point[others[1]] = first, second
                result.add(tuple(point))
                if limit is not None and len(result) >= limit:
                    return result, axis, False
    return result, axis, True


def boundary(solid):
    return {point for point in solid if any(add_voxel(point, step) not in solid for step in NEIGHBORS)}


def contiguous_columns(solid, axis):
    others = tuple(candidate for candidate in range(3) if candidate != axis)
    columns = {}
    for point in solid:
        columns.setdefault((point[others[0]], point[others[1]]), []).append(point[axis])
    return all(max(values) - min(values) + 1 == len(values) for values in columns.values())


def boundary_is_plane_owned(solid, planes):
    for point in boundary(solid):
        center = center_of(point)
        if not any(
            dot(normal, subtract(center, origin)) >= -EPSILON
            and any(
                dot(normal, subtract(center_of(add_voxel(point, step)), origin)) < -EPSILON
                for step in NEIGHBORS
            )
            for origin, normal in planes
        ):
            return False
    return True
