from __future__ import annotations

import unittest

from frontier_volume_3d import enclosed_air, nested_frontier_volume
from worker_surfaces import six_neighbor_boundary


class FrontierVolumeTest(unittest.TestCase):
    def test_face_output_axis_bridge_closes_reported_open_boundary_gap(self) -> None:
        arguments = (
            (0, 0, 0),
            (3, 3, 0),
            (-1, 1, 4),
            (6, -6, 3),
        )
        baseline = nested_frontier_volume(
            *arguments,
            "secondary_then_tertiary",
        )
        repaired = nested_frontier_volume(
            *arguments,
            "secondary_then_tertiary",
            True,
        )
        reported_gap = {
            (0, 1, 2),
            (1, 2, 2),
            (2, 3, 2),
        }

        self.assertEqual(reported_gap, set(baseline.missing_from_halfspace))
        self.assertEqual(reported_gap, set(repaired.base_output_axis_bridges))
        self.assertEqual(reported_gap, set(repaired.solid - baseline.solid))
        self.assertFalse(repaired.missing_from_halfspace)
        self.assertEqual(
            baseline.expansion_over_halfspace,
            repaired.expansion_over_halfspace,
        )
        self.assertEqual(233, len(baseline.solid))
        self.assertEqual(236, len(repaired.solid))

    def test_known_tilted_box_needs_secondary_then_tertiary_combination_bridge(self) -> None:
        arguments = (
            (0, 0, 0),
            (4, 0, 4),
            (-2, 4, 2),
            (-4, -4, 4),
        )
        parallel = nested_frontier_volume(*arguments, "parallel")
        ordered = nested_frontier_volume(*arguments, "secondary_then_tertiary")

        self.assertEqual(12, len(enclosed_air(parallel.solid)))
        self.assertFalse(enclosed_air(ordered.solid))
        self.assertEqual(261, len(ordered.solid))
        self.assertEqual(158, len(ordered.hollow))
        self.assertFalse(ordered.missing_owned_edge_voxels)
        self.assertEqual(six_neighbor_boundary(set(ordered.solid)), set(ordered.hollow))
        self.assertEqual(10, len(ordered.expansion_over_halfspace))
        self.assertEqual(8, len(ordered.missing_from_halfspace))

    def test_current_java_tilted_box_vector_has_expected_counts(self) -> None:
        volume = nested_frontier_volume(
            (0, 0, 0),
            (3, 3, 0),
            (-1, 1, 4),
            (6, -6, 3),
            "secondary_then_tertiary",
        )
        outline = set().union(*volume.owned_edges)
        self.assertEqual(233, len(volume.solid))
        self.assertEqual(158, len(volume.hollow))
        self.assertEqual(48, len(outline))
        self.assertTrue(outline <= volume.hollow)
        self.assertFalse(enclosed_air(volume.solid))
        self.assertEqual([0, 19, 12, 19, 12, 19, 12], [len(layer) for layer in volume.bridge_layers])

    def test_no_single_optional_axis_order_is_universally_hole_free(self) -> None:
        secondary_wins = (
            (0, 0, 0),
            (-4, 4, -1),
            (-1, -1, -3),
            (2, -4, 2),
        )
        tertiary_wins = (
            (0, 0, 0),
            (2, -1, -2),
            (-1, 4, -3),
            (4, 4, -4),
        )
        self.assertFalse(enclosed_air(nested_frontier_volume(
            *secondary_wins, "secondary_then_tertiary"
        ).solid))
        self.assertEqual(1, len(enclosed_air(nested_frontier_volume(
            *secondary_wins, "tertiary_then_secondary"
        ).solid)))
        self.assertEqual(1, len(enclosed_air(nested_frontier_volume(
            *tertiary_wins, "secondary_then_tertiary"
        ).solid)))
        self.assertFalse(enclosed_air(nested_frontier_volume(
            *tertiary_wins, "tertiary_then_secondary"
        ).solid))

    def test_union_of_both_abc_orders_still_has_an_ab_hole_counterexample(self) -> None:
        volume = nested_frontier_volume(
            (0, 0, 0),
            (-1, 2, 1),
            (2, 0, 2),
            (-2, -1, 0),
            "both_orders",
        )
        self.assertEqual({(-1, 1, 1)}, set(enclosed_air(volume.solid)))
        self.assertFalse(volume.missing_owned_edge_voxels)

    def test_owned_outline_can_become_solid_interior_even_without_a_hole(self) -> None:
        volume = nested_frontier_volume(
            (0, 0, 0),
            (-1, -2, -1),
            (0, -2, -1),
            (-1, 1, 2),
            "both_orders",
        )
        self.assertFalse(enclosed_air(volume.solid))
        self.assertEqual({(-1, -1, 1)}, set(volume.missing_owned_edge_voxels))
        self.assertIn((-1, -1, 1), volume.solid)


if __name__ == "__main__":
    unittest.main()
