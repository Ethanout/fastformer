from __future__ import annotations

import itertools
import unittest

from frontier_scan_3d import (
    canonical_nested_frontier_face,
    coverage,
    negate,
    nested_frontier_face,
)
from planar_faces import EPSILON, cross, length_squared


def vectors(radius: int) -> tuple[tuple[int, int, int], ...]:
    return tuple(
        vector
        for vector in itertools.product(range(-radius, radius + 1), repeat=3)
        if vector != (0, 0, 0)
    )


def non_degenerate(first: tuple[int, int, int], second: tuple[int, int, int]) -> bool:
    return length_squared(
        cross(tuple(float(value) for value in first), tuple(float(value) for value in second))
    ) > EPSILON


class NestedFrontierFaceTest(unittest.TestCase):
    def test_output_axis_bridge_thickens_only_existing_projection_columns(self) -> None:
        origin = (0, 0, 0)
        edge_a = (3, 3, 0)
        edge_b = (-1, 1, 4)
        baseline = canonical_nested_frontier_face(
            origin,
            edge_a,
            edge_b,
            True,
            True,
            True,
        )
        bridged = canonical_nested_frontier_face(
            origin,
            edge_a,
            edge_b,
            True,
            True,
            True,
            True,
        )
        bridge_voxels = set().union(*bridged.output_axis_bridge_layers)

        self.assertEqual({
            (-1, 0, 2),
            (0, 1, 2),
            (1, 2, 2),
            (2, 3, 2),
        }, bridge_voxels)
        self.assertEqual(baseline.projected_cells, bridged.projected_cells)
        self.assertTrue(all(
            bridged.projection.project_voxel(voxel) in baseline.projected_cells
            for voxel in bridge_voxels
        ))
        result = coverage(origin, edge_a, edge_b, bridged)
        self.assertFalse(result.holes)
        self.assertFalse(result.extras)

    def test_output_axis_bridge_preserves_small_canonical_projected_boundaries(self) -> None:
        checked = 0
        bridged_faces = 0
        emitted_voxels = 0
        added_voxels = 0
        candidates = vectors(2)
        for edge_a in candidates:
            for edge_b in candidates:
                if not non_degenerate(edge_a, edge_b):
                    continue
                baseline = canonical_nested_frontier_face(
                    (0, 0, 0), edge_a, edge_b, True, True, True
                )
                bridged = canonical_nested_frontier_face(
                    (0, 0, 0), edge_a, edge_b, True, True, True, True
                )
                bridge_voxels = set().union(*bridged.output_axis_bridge_layers)
                self.assertEqual(
                    baseline.projected_cells,
                    bridged.projected_cells,
                    (edge_a, edge_b),
                )
                self.assertTrue(all(
                    bridged.projection.project_voxel(voxel) in baseline.projected_cells
                    for voxel in bridge_voxels
                ), (edge_a, edge_b, bridge_voxels))
                result = coverage((0, 0, 0), edge_a, edge_b, bridged)
                self.assertFalse(result.holes, (edge_a, edge_b, result))
                self.assertFalse(result.extras, (edge_a, edge_b, result))
                self.assertFalse(result.missing_owned_edge_voxels, (edge_a, edge_b, result))
                checked += 1
                bridged_faces += bool(bridge_voxels)
                emitted_voxels += len(bridge_voxels)
                added_voxels += len(bridged.voxels - baseline.voxels)

        self.assertEqual(15_024, checked)
        self.assertEqual(10_480, bridged_faces)
        self.assertEqual(35_624, emitted_voxels)
        self.assertEqual(34_648, added_voxels)

    def test_polygon_clipping_removes_the_reported_bridge_overhang(self) -> None:
        edge_a = (-3, -3, -3)
        edge_b = (-3, -3, 3)
        unclipped = nested_frontier_face((0, 0, 0), edge_a, edge_b)
        clipped = nested_frontier_face((0, 0, 0), edge_a, edge_b, True)
        self.assertEqual(3, len(coverage((0, 0, 0), edge_a, edge_b, unclipped).extras))
        clipped_coverage = coverage((0, 0, 0), edge_a, edge_b, clipped)
        self.assertFalse(clipped_coverage.holes)
        self.assertFalse(clipped_coverage.extras)
        self.assertFalse(clipped_coverage.missing_owned_edge_voxels)

    def test_clipped_small_faces_have_no_holes_missing_edges_or_overhang(self) -> None:
        checked = 0
        thickness = {1: 0, 2: 0}
        candidates = vectors(2)
        for edge_a in candidates:
            for edge_b in candidates:
                if not non_degenerate(edge_a, edge_b):
                    continue
                face = nested_frontier_face((0, 0, 0), edge_a, edge_b, True)
                result = coverage((0, 0, 0), edge_a, edge_b, face)
                self.assertFalse(result.holes, (edge_a, edge_b, result))
                self.assertFalse(result.extras, (edge_a, edge_b, result))
                self.assertFalse(result.missing_owned_edge_voxels, (edge_a, edge_b, result))
                thickness[result.maximum_projection_column_thickness] += 1
                checked += 1
        self.assertEqual(15_024, checked)
        self.assertEqual({1: 12_944, 2: 2_080}, thickness)

    def test_clipping_bridge_cannot_remove_a_logical_slice_overhang(self) -> None:
        edge_a = (-3, -3, -2)
        edge_b = (-3, -2, -1)
        face = nested_frontier_face((0, 0, 0), edge_a, edge_b, True)
        result = coverage((0, 0, 0), edge_a, edge_b, face)
        self.assertEqual({(-4, -1)}, set(result.extras))
        extra_voxel = (-4, -3, -1)
        self.assertTrue(any(extra_voxel in section for section in face.logical_slices))
        self.assertFalse(any(extra_voxel in bridge for bridge in face.bridge_layers))

    def test_exhaustive_small_faces_have_no_projected_holes_and_keep_four_owned_edges(self) -> None:
        checked = 0
        extras = 0
        thick_columns = 0
        candidates = vectors(2)
        for edge_a in candidates:
            for edge_b in candidates:
                if not non_degenerate(edge_a, edge_b):
                    continue
                face = nested_frontier_face((0, 0, 0), edge_a, edge_b)
                result = coverage((0, 0, 0), edge_a, edge_b, face)
                self.assertFalse(result.holes, (edge_a, edge_b, result))
                self.assertFalse(result.missing_owned_edge_voxels, (edge_a, edge_b, result))
                self.assertLessEqual(result.maximum_projection_column_thickness, 2)
                extras += bool(result.extras)
                thick_columns += result.maximum_projection_column_thickness > 1
                checked += 1
        self.assertEqual(15_024, checked)
        self.assertEqual(10_880, extras)
        self.assertEqual(2_088, thick_columns)

    def test_bridge_voxels_are_not_part_of_the_next_logical_pi(self) -> None:
        face = nested_frontier_face(
            (0, 0, 0),
            (-3, -2, -1),
            (-3, -1, -2),
        )
        non_empty = [
            (index, bridge)
            for index, bridge in enumerate(face.bridge_layers)
            if bridge
        ]
        self.assertEqual(1, len(non_empty))
        index, bridge = non_empty[0]
        self.assertFalse(bridge <= face.logical_slices[index])

    def test_preserving_owned_three_axis_edges_can_make_a_projection_column_three_voxels_thick(self) -> None:
        edge_a = (-3, -2, -1)
        edge_b = (-3, -1, -2)
        face = nested_frontier_face((0, 0, 0), edge_a, edge_b)
        result = coverage((0, 0, 0), edge_a, edge_b, face)
        self.assertFalse(result.holes)
        self.assertFalse(result.missing_owned_edge_voxels)
        self.assertEqual(3, result.maximum_projection_column_thickness)

    def test_raw_bridge_side_depends_on_sweep_direction(self) -> None:
        edge_a = (-2, -2, -2)
        edge_b = (-2, -2, -1)
        forward = nested_frontier_face((0, 0, 0), edge_a, edge_b)
        backward = nested_frontier_face(edge_b, edge_a, negate(edge_b))
        self.assertNotEqual(forward.voxels, backward.voxels)
        self.assertEqual(4, len(forward.voxels ^ backward.voxels))

    def test_canonical_sweep_direction_removes_small_face_reversal_differences(self) -> None:
        checked = 0
        candidates = vectors(2)
        for edge_a in candidates:
            for edge_b in candidates:
                if not non_degenerate(edge_a, edge_b):
                    continue
                forward = canonical_nested_frontier_face((0, 0, 0), edge_a, edge_b)
                backward = canonical_nested_frontier_face(edge_b, edge_a, negate(edge_b))
                self.assertEqual(forward.voxels, backward.voxels, (edge_a, edge_b))
                checked += 1
        self.assertEqual(15_024, checked)


if __name__ == "__main__":
    unittest.main()
