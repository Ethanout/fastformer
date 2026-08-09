from itertools import permutations, product
import unittest

from canonical_volume_lab import (
    axis_intervals_are_contiguous, has_enclosed_air, is_six_connected,
    maximum_boundary_deviation, planes_for, rasterize, six_boundary,
)
from planar_faces import add


def transform(point, order, signs):
    return tuple(signs[i] * point[order[i]] for i in range(3))


class CanonicalVolumeLabTest(unittest.TestCase):
    base = ((.5, .5, .5), (9.5, 3.5, 6.5), (5.5, 11.5, 10.5), (-3.5, 8.5, 4.5))
    extrusion = (7., -5., 9.)

    def test_surface_is_owned_and_scan_intervals_are_complete(self):
        volume = rasterize(self.base, self.extrusion)
        self.assertTrue(volume.solid)
        self.assertTrue(axis_intervals_are_contiguous(volume))
        self.assertEqual(six_boundary(volume.solid), volume.boundary)
        self.assertFalse(has_enclosed_air(volume.solid))
        self.assertLessEqual(maximum_boundary_deviation(volume, planes_for(self.base, self.extrusion)), 1.0 + 1e-9)

    def test_all_signed_axis_transforms_preserve_invariants(self):
        for order in permutations(range(3)):
            for signs in product((-1, 1), repeat=3):
                base = tuple(transform(point, order, signs) for point in self.base)
                extrusion = transform(self.extrusion, order, signs)
                volume = rasterize(base, extrusion)
                self.assertTrue(axis_intervals_are_contiguous(volume), (order, signs))
                self.assertEqual(six_boundary(volume.solid), volume.boundary, (order, signs))
                self.assertFalse(has_enclosed_air(volume.solid), (order, signs))

    def test_progressive_iterator_is_unique_complete_and_balanced(self):
        volume = rasterize(self.base, self.extrusion)
        output = tuple(volume.progressive())
        self.assertEqual(len(output), len(set(output)))
        self.assertEqual(set(output), volume.solid)
        limit = min(37, len(output))
        prefix = set(output[:limit])
        self.assertEqual(limit, len(prefix))
        # The first budget already covers more than one mathematical side;
        # iteration is not a complete translated base-face prefix.
        self.assertGreaterEqual(sum(bool(prefix & face) for face in volume.faces), 2)

    def test_mid_sweep_translates_are_not_an_ownership_source(self):
        volume = rasterize(self.base, self.extrusion)
        thirds = tuple(add(point, tuple(value / 3 for value in self.extrusion)) for point in self.base)
        middle = rasterize(thirds, tuple(value / 20 for value in self.extrusion)).solid
        exposed_middle = middle & volume.boundary
        self.assertTrue(exposed_middle)  # geometric coincidence is allowed
        self.assertTrue(all(any(voxel in face for face in volume.faces) for voxel in exposed_middle))


if __name__ == "__main__":
    unittest.main()
