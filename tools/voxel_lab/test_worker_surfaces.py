from __future__ import annotations

import itertools
import unittest

from planar_faces import parallelogram
from worker_surfaces import (
    extent,
    face_coverage,
    owned_edge_internal_l_pixels,
    owned_edges,
    projected_boundary_scan_face,
    projected_lockstep_face,
    right_angle_at,
    six_neighbor_boundary,
    worker_parallelepiped,
)


def convex_hull(points: tuple[tuple[int, int], ...]) -> tuple[tuple[int, int], ...]:
    ordered = sorted(set(points))

    def turn(origin: tuple[int, int], first: tuple[int, int], second: tuple[int, int]) -> int:
        return (
            (first[0] - origin[0]) * (second[1] - origin[1])
            - (first[1] - origin[1]) * (second[0] - origin[0])
        )

    lower: list[tuple[int, int]] = []
    for point in ordered:
        while len(lower) >= 2 and turn(lower[-2], lower[-1], point) <= 0:
            lower.pop()
        lower.append(point)
    upper: list[tuple[int, int]] = []
    for point in reversed(ordered):
        while len(upper) >= 2 and turn(upper[-2], upper[-1], point) <= 0:
            upper.pop()
        upper.append(point)
    return tuple(lower[:-1] + upper[:-1])


class EdgeOwnershipTest(unittest.TestCase):
    def test_each_owned_edge_has_no_internal_l_pixel(self) -> None:
        edges = owned_edges(((0, 0), (8, 3), (5, 10), (-3, 7)))
        self.assertEqual(4, len(edges))
        for edge in edges:
            self.assertFalse(owned_edge_internal_l_pixels(edge), edge)

    def test_shared_geometry_corner_may_be_an_l_pixel(self) -> None:
        vertices = ((0, 0), (6, 0), (6, 4), (0, 4))
        edges = owned_edges(vertices)
        outline = {pixel for edge in edges for pixel in edge.pixels}
        self.assertTrue(right_angle_at(outline, vertices[0]))
        self.assertFalse(any(
            right_angle_at(outline, pixel)
            for pixel in outline - set(vertices)
        ))

    def test_global_set_degree_cannot_replace_edge_ownership(self) -> None:
        # These two almost parallel edge pairs are closer than one voxel.  The
        # union has apparent non-corner L pixels although each owned line is
        # individually valid.  A global HashSet therefore cannot express the
        # user's corner exception without false positives.
        vertices = ((0, 0), (-6, -6), (-12, -11), (-6, -5))
        edges = owned_edges(vertices)
        outline = {pixel for edge in edges for pixel in edge.pixels}
        self.assertTrue(any(
            right_angle_at(outline, pixel)
            for pixel in outline - set(vertices)
        ))
        for edge in edges:
            self.assertFalse(owned_edge_internal_l_pixels(edge), edge)


class ProjectedWorkerFaceTest(unittest.TestCase):
    def test_sparse_two_rail_lockstep_has_a_parity_hole(self) -> None:
        vertices = (
            (0.5, 0.5, 0.5),
            (-2.5, -2.5, -2.5),
            (-5.5, -5.5, -1.5),
            (-2.5, -2.5, 1.5),
        )
        expected_holes = {(-4, -2), (-3, -1), (-2, 0)}
        for rail_pair in (0, 1):
            worker = projected_lockstep_face(vertices, rail_pair)
            self.assertEqual(expected_holes, set(face_coverage(vertices, worker).holes))

    def test_full_strip_fill_closes_the_lockstep_parity_hole(self) -> None:
        vertices = (
            (0.5, 0.5, 0.5),
            (-2.5, -2.5, -2.5),
            (-5.5, -5.5, -1.5),
            (-2.5, -2.5, 1.5),
        )
        coverage = face_coverage(vertices, projected_boundary_scan_face(vertices))
        self.assertFalse(coverage.holes)
        self.assertFalse(coverage.extras)

    def test_boundary_worker_scan_matches_small_convex_quad_rasters(self) -> None:
        points = tuple(itertools.product(range(-2, 3), repeat=2))
        checked = 0
        for combination in itertools.combinations(points, 4):
            hull = convex_hull(combination)
            if len(hull) != 4:
                continue
            vertices = tuple((first + 0.5, second + 0.5, 0.5) for first, second in hull)
            coverage = face_coverage(vertices, projected_boundary_scan_face(vertices))
            self.assertFalse(coverage.holes, (vertices, coverage))
            self.assertFalse(coverage.extras, (vertices, coverage))
            checked += 1
        self.assertGreater(checked, 5_000)

    def test_sparse_lockstep_is_safe_when_one_projected_generator_is_axis_aligned(self) -> None:
        checked = 0
        vectors = tuple(
            vector
            for vector in itertools.product(range(-4, 5), repeat=2)
            if vector != (0, 0)
        )
        for first in vectors:
            for second in vectors:
                if first[0] * second[1] - first[1] * second[0] == 0:
                    continue
                if not (
                    first[0] == 0
                    or first[1] == 0
                    or second[0] == 0
                    or second[1] == 0
                ):
                    continue
                vertices = parallelogram(
                    (0.5, 0.5, 0.5),
                    (float(first[0]), float(first[1]), 0.0),
                    (float(second[0]), float(second[1]), 0.0),
                )
                coverage = face_coverage(vertices, projected_lockstep_face(vertices, 0))
                self.assertFalse(coverage.holes, (first, second, coverage))
                self.assertFalse(coverage.extras, (first, second, coverage))
                checked += 1
        self.assertGreater(checked, 1_000)

    def test_scan_fill_is_convex_only(self) -> None:
        concave = tuple(
            (first + 0.5, second + 0.5, 0.5)
            for first, second in ((0, 0), (0, 3), (1, 1), (7, 5))
        )
        coverage = face_coverage(concave, projected_boundary_scan_face(concave))
        self.assertEqual({(1, 3), (2, 3), (3, 3)}, set(coverage.extras))


class WorkerVolumeBoundaryTest(unittest.TestCase):
    def test_independent_worker_faces_can_miss_exposed_solid_boundary_voxels(self) -> None:
        solid, face_shell, _ = worker_parallelepiped(
            (0, 0, 0),
            (4, 0, 4),
            (-2, 4, 2),
            (-4, -4, 4),
        )
        boundary = six_neighbor_boundary(solid)
        self.assertFalse(face_shell - boundary)
        self.assertEqual(27, len(boundary - face_shell))
        self.assertEqual(extent(face_shell), extent(boundary))

    def test_independent_worker_faces_can_also_become_solid_interior(self) -> None:
        solid, face_shell, _ = worker_parallelepiped(
            (0, 0, 0),
            (4, 1, 0),
            (0, 4, 1),
            (1, 1, 4),
        )
        boundary = six_neighbor_boundary(solid)
        self.assertEqual(2, len(face_shell - boundary))
        self.assertFalse(boundary - face_shell)
        self.assertEqual(extent(face_shell), extent(boundary))

    def test_axis_aligned_cross_section_is_a_safe_worker_volume_condition(self) -> None:
        checked = 0
        for extrusion in itertools.product(range(-3, 4), repeat=3):
            if extrusion == (0, 0, 0) or extrusion[2] == 0:
                continue
            solid, face_shell, owned_outline = worker_parallelepiped(
                (0, 0, 0),
                (3, 0, 0),
                (0, 2, 0),
                extrusion,
            )
            boundary = six_neighbor_boundary(solid)
            self.assertEqual(boundary, face_shell, extrusion)
            self.assertTrue(set().union(*owned_outline) <= boundary, extrusion)
            checked += 1
        self.assertGreater(checked, 100)


if __name__ == "__main__":
    unittest.main()
