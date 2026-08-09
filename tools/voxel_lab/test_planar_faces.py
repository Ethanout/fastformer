from __future__ import annotations

import itertools
import unittest
from typing import Sequence

from planar_faces import (
    EPSILON,
    Projection,
    add,
    bresenham_path_2d,
    bresenham_projected_face,
    containing,
    coverage,
    cross,
    current_quad_sweep,
    dominant_axis_face,
    face_normal,
    length_squared,
    nested_bresenham_path,
    parallelogram,
    projected_boundary_cells,
    projected_cells,
    projected_edge_path,
    scanline_worker_face,
    scale,
)


class DominantAxisFaceTest(unittest.TestCase):
    def test_old_row_sweep_has_projected_holes(self) -> None:
        vertices = parallelogram(
            (0.5, 0.5, 0.5),
            (-3.0, -3.0, -2.0),
            (-3.0, -2.0, 3.0),
        )
        self.assertGreater(len(coverage(vertices, current_quad_sweep(vertices)).holes), 0)

    def test_axis_aligned_face_has_expected_grid(self) -> None:
        vertices = parallelogram((0.5, 0.5, 0.5), (4.0, 0.0, 0.0), (0.0, 0.0, 3.0))
        result = dominant_axis_face(vertices)
        self.assertEqual(20, len(result))
        self.assertEqual({(x, 0, z) for x in range(5) for z in range(4)}, result)

    def test_exhaustive_small_parallelograms_have_one_voxel_per_projected_cell(self) -> None:
        origin = (0.5, 0.5, 0.5)
        vectors = [
            tuple(float(value) for value in vector)
            for vector in itertools.product(range(-2, 3), repeat=3)
            if vector != (0, 0, 0)
        ]
        checked = 0
        for edge_a in vectors:
            for edge_b in vectors:
                if length_squared(cross(edge_a, edge_b)) <= EPSILON:
                    continue
                vertices = parallelogram(origin, edge_a, edge_b)
                result = coverage(vertices, dominant_axis_face(vertices))
                self.assertFalse(result.holes, (vertices, result))
                self.assertFalse(result.extras, (vertices, result))
                self.assertEqual(1, result.maximum_column_thickness, vertices)
                checked += 1
        self.assertGreater(checked, 10_000)

    def test_vertex_order_does_not_change_voxels(self) -> None:
        vertices = parallelogram((0.5, 0.5, 0.5), (5.0, 1.0, -2.0), (-2.0, 4.0, 3.0))
        expected = dominant_axis_face(vertices)
        self.assertEqual(expected, dominant_axis_face(vertices[1:] + vertices[:1]))
        self.assertEqual(expected, dominant_axis_face(tuple(reversed(vertices))))

    def test_concave_notch_is_not_filled(self) -> None:
        vertices = (
            (0.5, 0.5, 0.5),
            (4.5, 0.5, 0.5),
            (4.5, 1.5, 1.5),
            (1.5, 1.5, 1.5),
            (1.5, 4.5, 4.5),
            (0.5, 4.5, 4.5),
        )
        result = dominant_axis_face(vertices)
        self.assertIn((3, 0, 0), result)
        self.assertIn((0, 3, 3), result)
        self.assertNotIn((3, 3, 3), result)

    def test_every_voxel_is_the_plane_equation_solution_for_its_column(self) -> None:
        vertices = parallelogram((0.5, 0.5, 0.5), (7.0, -2.0, 3.0), (-1.0, 5.0, 4.0))
        normal = face_normal(vertices)
        projection = Projection.for_normal(normal)
        result = dominant_axis_face(vertices)
        for voxel in result:
            first, second = projection.project_voxel(voxel)
            solved = projection.restore(first, second, vertices[0], normal)
            self.assertEqual(solved, voxel)

    def test_limit_stops_before_exceeding_output_budget(self) -> None:
        vertices = parallelogram((0.5, 0.5, 0.5), (20.0, 4.0, 1.0), (-3.0, 12.0, 8.0))
        self.assertEqual(17, len(dominant_axis_face(vertices, 17)))


class BresenhamProjectedFaceTest(unittest.TestCase):
    def test_two_dimensional_edge_has_no_l_pixel_and_is_reversal_stable(self) -> None:
        for first in range(-8, 9):
            for second in range(-8, 9):
                end = first, second
                forward = bresenham_path_2d((0, 0), end)
                backward = bresenham_path_2d(end, (0, 0))
                self.assertEqual(set(forward), set(backward), end)
                pixels = set(forward)
                for center in pixels:
                    horizontal = any(
                        (center[0] + offset, center[1]) in pixels
                        for offset in (-1, 1)
                    )
                    vertical = any(
                        (center[0], center[1] + offset) in pixels
                        for offset in (-1, 1)
                    )
                    self.assertFalse(horizontal and vertical, (end, center, pixels))

    def test_face_uses_edge_boundary_before_plane_restore(self) -> None:
        vertices = parallelogram(
            (0.5, 0.5, 0.5),
            (7.0, -2.0, 3.0),
            (-1.0, 5.0, 4.0),
        )
        normal = face_normal(vertices)
        projection = Projection.for_normal(normal)
        boundary = projected_boundary_cells(vertices, projection)
        result = bresenham_projected_face(vertices)
        projected_result = {projection.project_voxel(voxel) for voxel in result}

        self.assertTrue(boundary <= projected_result)
        self.assertEqual(
            projected_cells(vertices, projection) | boundary,
            projected_result,
        )
        for voxel in result:
            first, second = projection.project_voxel(voxel)
            self.assertEqual(
                projection.restore(first, second, vertices[0], normal),
                voxel,
            )

    def test_face_boundary_and_fill_have_identical_projected_extents(self) -> None:
        vertices = parallelogram(
            (0.5, 0.5, 0.5),
            (9.0, 4.0, -3.0),
            (-2.0, 3.0, 6.0),
        )
        normal = face_normal(vertices)
        projection = Projection.for_normal(normal)
        boundary = projected_boundary_cells(vertices, projection)
        face = {
            projection.project_voxel(voxel)
            for voxel in bresenham_projected_face(vertices)
        }

        self.assertEqual(
            (
                min(cell[0] for cell in boundary),
                max(cell[0] for cell in boundary),
                min(cell[1] for cell in boundary),
                max(cell[1] for cell in boundary),
            ),
            (
                min(cell[0] for cell in face),
                max(cell[0] for cell in face),
                min(cell[1] for cell in face),
                max(cell[1] for cell in face),
            ),
        )

    def test_scanline_worker_face_has_no_projected_holes_and_keeps_owned_edges(self) -> None:
        origin = (0.5, 0.5, 0.5)
        vectors = [
            tuple(float(value) for value in vector)
            for vector in itertools.product(range(-2, 3), repeat=3)
            if vector != (0, 0, 0)
        ]
        checked = 0
        for edge_a in vectors:
            for edge_b in vectors:
                if length_squared(cross(edge_a, edge_b)) <= EPSILON:
                    continue
                vertices = parallelogram(origin, edge_a, edge_b)
                face, owned_edges = scanline_worker_face(vertices)
                self.assertFalse(coverage(vertices, face).holes, vertices)
                self.assertTrue(set().union(*(set(edge) for edge in owned_edges)) <= face)
                for edge in owned_edges:
                    self.assert_edge_projections_have_no_l_pixel(edge)
                checked += 1
        self.assertGreater(checked, 10_000)

    def test_nested_line_matches_equal_minor_axis_visual_rule(self) -> None:
        path = nested_bresenham_path((0, 0, 0), (4, 11, 4))
        for previous, current in zip(path, path[1:]):
            changed = {
                axis
                for axis in range(3)
                if previous[axis] != current[axis]
            }
            self.assertIn(changed, ({1}, {0, 1, 2}))

    def test_plane_restore_counterexample_requires_owned_three_axis_workers(self) -> None:
        origin = (0.5, 0.5, 0.5)
        edge = (-3.0, -2.0, -1.0)
        second_edge = (-3.0, -3.0, -1.0)
        normal = cross(edge, second_edge)
        projection = Projection.for_normal(normal)
        restored = projected_edge_path(
            origin,
            add(origin, edge),
            origin,
            normal,
            projection,
        )

        self.assertEqual(
            ((0, 0, 0), (-1, -1, 0), (-2, -1, -1), (-3, -2, -1)),
            restored,
        )
        with self.assertRaises(AssertionError):
            self.assert_edge_projections_have_no_l_pixel(restored)

    def assert_edge_projections_have_no_l_pixel(self, edge: Sequence[tuple[int, int, int]]) -> None:
        for dropped_axis in range(3):
            axes = [axis for axis in range(3) if axis != dropped_axis]
            pixels = {(voxel[axes[0]], voxel[axes[1]]) for voxel in edge}
            for center in pixels:
                horizontal = any(
                    (center[0] + offset, center[1]) in pixels
                    for offset in (-1, 1)
                )
                vertical = any(
                    (center[0], center[1] + offset) in pixels
                    for offset in (-1, 1)
                )
                self.assertFalse(horizontal and vertical, (edge, dropped_axis, center))


if __name__ == "__main__":
    unittest.main()
