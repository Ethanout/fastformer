"""Canonical 2-D Bresenham coverage lifted to one voxel per projected cell."""
from collections import Counter
from dataclasses import dataclass
from itertools import permutations, product
from math import floor
from pathlib import Path

from planar_faces import bresenham_path_2d, containing, face_normal


def signed_permutations():
    return tuple((order, signs) for order in permutations(range(3)) for signs in product((-1, 1), repeat=3))


@dataclass(frozen=True)
class Frame:
    anchor: tuple[float, float, float]
    order: tuple[int, int, int]
    signs: tuple[int, int, int]

    def vector(self, point):
        return tuple(self.signs[i] * (point[self.order[i]] - self.anchor[self.order[i]]) for i in range(3))

    def world_voxel(self, local):
        center = list(self.anchor)
        for i in range(3):
            center[self.order[i]] += self.signs[i] * local[i]
        return containing(tuple(center))

    def project_voxel(self, voxel):
        local = self.vector(tuple(value + .5 for value in voxel))
        return round(local[0]), round(local[1])


def canonical_frame(vertices):
    """Choose one face-wide signed axis transform from the complete 48-case orbit."""
    best = None
    minimum = min(range(len(vertices)), key=lambda i: vertices[i])
    for start in (minimum,):
        for direction in (1, -1):
            ordered = tuple(vertices[(start + direction * i) % len(vertices)] for i in range(len(vertices)))
            for order, signs in signed_permutations():
                frame = Frame(ordered[0], order, signs)
                local = tuple(frame.vector(vertex) for vertex in ordered)
                normal = face_normal(local)
                if abs(normal[2]) + 1e-9 < max(map(abs, normal)):
                    continue
                key = tuple(round(value, 9) for vertex in local[1:] for value in vertex) + order + signs
                if best is None or key < best[0]:
                    best = key, frame, local
    if best is None:
        raise ValueError("degenerate face")
    return best[1], best[2]


def projected_face(local_vertices):
    corners = tuple((round(vertex[0]), round(vertex[1])) for vertex in local_vertices)
    edges = tuple(bresenham_path_2d(corners[i], corners[(i + 1) % len(corners)]) for i in range(len(corners)))
    rows = {}
    for edge in edges:
        for u, v in edge:
            rows.setdefault(v, []).append(u)
    cells = {(u, v) for v, hits in rows.items() for u in range(min(hits), max(hits) + 1)}
    return cells, edges


def lift(cell, normal):
    """One global digital-plane phase; canonicalization resolves exact-half ties."""
    u, v = cell
    real_w = -(normal[0] * u + normal[1] * v) / normal[2]
    return u, v, floor(real_w + .5)


def scan_face(vertices):
    frame, local_vertices = canonical_frame(vertices)
    normal = face_normal(local_vertices)
    cells, edges_2d = projected_face(local_vertices)
    to_world = lambda cell: frame.world_voxel(lift(cell, normal))
    edges = tuple(tuple(to_world(cell) for cell in edge) for edge in edges_2d)
    return {to_world(cell) for cell in cells}, edges, frame


def thickness(points, frame):
    return Counter(frame.project_voxel(point) for point in points)


def transform_point(point, order, signs):
    return tuple(signs[i] * point[order[i]] for i in range(3))


def transform_voxel(voxel, order, signs):
    return containing(transform_point(tuple(value + .5 for value in voxel), order, signs))


def render(vertices, output=None):
    import matplotlib.pyplot as plt
    import numpy as np

    points, edges, frame = scan_face(vertices)
    figure = plt.figure(figsize=(12, 5)); axis = figure.add_subplot(121, projection="3d")
    data = np.array(sorted(points)); shifted = data - data.min(axis=0)
    voxels = np.zeros(tuple(shifted.max(axis=0) + 1), dtype=bool)
    for point in shifted: voxels[tuple(point)] = True
    axis.voxels(voxels, facecolors="#72bfff", edgecolor="#17324d", linewidth=.25)
    axis.set_title("canonical two-stage Bresenham face")
    shadow = {frame.project_voxel(point) for point in points}
    boundary = {frame.project_voxel(point) for edge in edges for point in edge}
    flat = figure.add_subplot(122)
    for u, v in shadow: flat.add_patch(plt.Rectangle((u, v), 1, 1, facecolor="#72bfff", edgecolor="#17324d", linewidth=.3))
    for u, v in boundary: flat.add_patch(plt.Rectangle((u, v), 1, 1, fill=False, edgecolor="#ff7f0e", linewidth=1.1))
    flat.autoscale(); flat.set_aspect("equal"); flat.set_title("UV coverage; orange = shared outline")
    figure.tight_layout(); output = Path(output or Path(__file__).with_suffix(".png")); figure.savefig(output, dpi=180)
    return output


if __name__ == "__main__":
    origin = (.5, .5, .5); a = (12., 5., 3.); b = (5., 10., -2.)
    vertices = (origin, tuple(origin[i] + a[i] for i in range(3)),
                tuple(origin[i] + a[i] + b[i] for i in range(3)), tuple(origin[i] + b[i] for i in range(3)))
    face, _, frame = scan_face(vertices)
    print(f"voxels={len(face)} max_thickness={max(thickness(face, frame).values())}"); print(render(vertices))
