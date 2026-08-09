"""Non-recursive risers for a one-layer digital face."""
from collections import Counter, deque
from pathlib import Path

from bresenham_scan_lab import canonical_frame, lift, scan_face
from planar_faces import contains, face_normal


def local_columns(points, frame):
    result = {}
    for point in points:
        local = frame.vector(tuple(value + .5 for value in point))
        result[(round(local[0]), round(local[1]))] = round(local[2])
    return result


def cracks(columns):
    result = []
    for cell, w in columns.items():
        for step in ((1, 0), (0, 1)):
            neighbor = cell[0] + step[0], cell[1] + step[1]
            if neighbor in columns and columns[neighbor] != w:
                result.append((cell, neighbor))
    return result


def close_projection(cells, local_vertices):
    """Replace each diagonal-only 2-D contact with one inward orthogonal cell."""
    polygon = tuple((vertex[0], vertex[1]) for vertex in local_vertices)
    result = set(cells)
    for u, v in sorted(cells):
        for du, dv in ((1, 1), (1, -1)):
            diagonal = u + du, v + dv
            first, second = (u + du, v), (u, v + dv)
            if diagonal not in cells or first in cells or second in cells:
                continue
            result.add(min((first, second), key=lambda cell: (not contains(polygon, (cell[0] + .5, cell[1] + .5)), cell)))
    return result


def bridge_face(vertices):
    base, _, frame = scan_face(vertices)
    _, local_vertices = canonical_frame(vertices)
    normal = face_normal(local_vertices)
    base_columns = local_columns(base, frame)
    closed_cells = close_projection(set(base_columns), local_vertices)
    logical = {frame.world_voxel(lift(cell, normal)) for cell in closed_cells}
    columns = local_columns(logical, frame)
    gaps = cracks(columns)

    def candidates(upper_side):
        result = set()
        for first, second in gaps:
            low = first if columns[first] < columns[second] else second
            high = second if low == first else first
            cell = low if upper_side else high
            w = columns[cell] + (1 if upper_side else -1)
            result.add((cell[0], cell[1], w))
        return result

    def cost(points):
        return sum(abs(normal[0] * u + normal[1] * v + normal[2] * w) for u, v, w in points)

    upper, lower = candidates(True), candidates(False)
    local_bridges = min((upper, lower), key=lambda points: (len(points), cost(points), sorted(points)))
    bridges = {frame.world_voxel(point) for point in local_bridges}
    return logical | bridges, logical, bridges, frame


def is_six_connected(points):
    if not points:
        return True
    seen, queue = set(), deque((next(iter(points)),))
    while queue:
        point = queue.popleft()
        if point in seen:
            continue
        seen.add(point)
        for axis in range(3):
            for step in (-1, 1):
                neighbor = list(point); neighbor[axis] += step; neighbor = tuple(neighbor)
                if neighbor in points and neighbor not in seen:
                    queue.append(neighbor)
    return len(seen) == len(points)


def maximum_thickness(points, frame):
    counts = Counter()
    for point in points:
        local = frame.vector(tuple(value + .5 for value in point))
        counts[(round(local[0]), round(local[1]))] += 1
    return max(counts.values(), default=0)


def render(vertices, output=None):
    import matplotlib.pyplot as plt
    import numpy as np

    filled, logical, bridges, _ = bridge_face(vertices)
    figure = plt.figure(figsize=(12, 5))
    for index, (points, title) in enumerate(((logical, "single-layer logical"), (filled, "minimal non-recursive risers")), 1):
        axis = figure.add_subplot(1, 2, index, projection="3d")
        data = np.array(sorted(points)); shifted = data - data.min(axis=0)
        shape = tuple(shifted.max(axis=0) + 1); voxels = np.zeros(shape, dtype=bool)
        colors = np.empty(shape, dtype=object)
        for world, point in zip(data, shifted):
            key = tuple(point); voxels[key] = True; colors[key] = "#ff9f43" if tuple(world) in bridges else "#72bfff"
        axis.voxels(voxels, facecolors=colors, edgecolor="#17324d", linewidth=.2); axis.set_title(title)
    figure.tight_layout(); output = Path(output or Path(__file__).with_suffix(".png")); figure.savefig(output, dpi=180)
    return output


if __name__ == "__main__":
    origin = (.5, .5, .5); a = (12., 5., 3.); b = (5., 10., -2.)
    vertices = (origin, tuple(origin[i] + a[i] for i in range(3)),
                tuple(origin[i] + a[i] + b[i] for i in range(3)), tuple(origin[i] + b[i] for i in range(3)))
    filled, logical, bridges, frame = bridge_face(vertices)
    print(f"logical={len(logical)} bridges={len(bridges)} max_thickness={maximum_thickness(filled, frame)} six={is_six_connected(filled)}")
    print(render(vertices))
