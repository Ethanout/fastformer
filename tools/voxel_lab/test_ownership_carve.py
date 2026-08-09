from __future__ import annotations

import unittest

from frontier_volume_3d import enclosed_air
from ownership_carve import OwnershipCarveResult, ownership_carve, secondary_volume


def assert_final_contract(test: unittest.TestCase, result: OwnershipCarveResult) -> None:
    test.assertFalse(result.remaining_enclosed_air)
    test.assertTrue(result.outline <= result.boundary)
    test.assertTrue(result.outline <= result.solid)


class OwnershipCarveTest(unittest.TestCase):
    def test_known_tilted_box_target_is_unchanged(self) -> None:
        volume = secondary_volume(
            (0, 0, 0),
            (4, 0, 4),
            (-2, 4, 2),
            (-4, -4, 4),
        )
        result = ownership_carve(volume)
        assert_final_contract(self, result)
        self.assertEqual(volume.solid, result.solid)
        self.assertFalse(result.initially_filled_air)
        self.assertEqual(0, result.deleted_voxels)
        self.assertEqual(0, result.maximum_carve_depth)

    def test_current_java_tilted_box_target_is_unchanged(self) -> None:
        volume = secondary_volume(
            (0, 0, 0),
            (3, 3, 0),
            (-1, 1, 4),
            (6, -6, 3),
        )
        result = ownership_carve(volume)
        assert_final_contract(self, result)
        self.assertEqual(volume.solid, result.solid)
        self.assertEqual(233, len(result.solid))
        self.assertEqual(158, len(result.boundary))
        self.assertEqual(0, result.deleted_voxels)

    def test_order_counterexample_only_fills_one_enclosed_voxel(self) -> None:
        volume = secondary_volume(
            (0, 0, 0),
            (2, -1, -2),
            (-1, 4, -3),
            (4, 4, -4),
        )
        self.assertEqual(1, len(enclosed_air(volume.solid)))
        result = ownership_carve(volume)
        assert_final_contract(self, result)
        self.assertEqual(1, len(result.initially_filled_air))
        self.assertEqual(0, result.deleted_voxels)
        self.assertEqual(len(volume.solid) + 1, len(result.solid))

    def test_opposite_order_witness_needs_no_repair_under_secondary_first(self) -> None:
        volume = secondary_volume(
            (0, 0, 0),
            (-4, 4, -1),
            (-1, -1, -3),
            (2, -4, 2),
        )
        result = ownership_carve(volume)
        assert_final_contract(self, result)
        self.assertEqual(volume.solid, result.solid)
        self.assertEqual(0, result.deleted_voxels)

    def test_ab_counterexample_only_fills_one_enclosed_voxel(self) -> None:
        volume = secondary_volume(
            (0, 0, 0),
            (-1, 2, 1),
            (2, 0, 2),
            (-2, -1, 0),
        )
        self.assertEqual({(-1, 1, 1)}, set(enclosed_air(volume.solid)))
        result = ownership_carve(volume)
        assert_final_contract(self, result)
        self.assertEqual({(-1, 1, 1)}, set(result.initially_filled_air))
        self.assertEqual(0, result.deleted_voxels)

    def test_interior_owned_edge_needs_one_voxel_depth_one_carve(self) -> None:
        volume = secondary_volume(
            (0, 0, 0),
            (-1, -2, -1),
            (0, -2, -1),
            (-1, 1, 2),
        )
        self.assertEqual({(-1, -1, 1)}, set(volume.missing_owned_edge_voxels))
        result = ownership_carve(volume)
        assert_final_contract(self, result)
        self.assertEqual({(-1, -1, 2)}, set(result.forbidden_air))
        self.assertEqual(1, result.deleted_voxels)
        self.assertEqual(1, result.maximum_carve_depth)
        self.assertFalse(result.finally_filled_air)


if __name__ == "__main__":
    unittest.main()
