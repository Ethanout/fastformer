import itertools
import random
import unittest

from canonical_column_volume import (
    boundary,
    boundary_is_plane_owned,
    column_solid,
    contiguous_columns,
    inward_planes,
)
from closed_volumes import quad_halfspace_solid
from bresenham_scan_lab import scan_face
from planar_faces import EPSILON, cross, dot, length_squared, nested_bresenham_path, parallelogram


class CanonicalColumnVolumeTest(unittest.TestCase):
    def test_translated_sections_expose_worker_ribs_outside_the_canonical_volume(self):
        base = parallelogram((.5, .5, .5), (-3., 8., -1.), (7., -2., -6.))
        extrusion = (-5., 0., -1.)
        logical = scan_face(base)[0]
        offsets = nested_bresenham_path((0, 0, 0), tuple(map(int, extrusion)))
        layered = {
            tuple(point[axis] + offset[axis] for axis in range(3))
            for point in logical for offset in offsets
        }
        canonical, axis, _ = column_solid(base, extrusion)
        self.assertGreater(len(boundary(layered) - canonical), 100)
        self.assertTrue(contiguous_columns(canonical, axis))

    def test_random_small_boxes_equal_the_halfspace_reference(self):
        randomizer = random.Random(233)
        origin = (.5, .5, .5)
        checked = 0
        while checked < 2_000:
            vectors = [tuple(float(randomizer.randint(-5, 5)) for _ in range(3)) for _ in range(3)]
            first, second, extrusion = vectors
            normal = cross(first, second)
            if (0., 0., 0.) in vectors or length_squared(normal) <= EPSILON or abs(dot(normal, extrusion)) <= EPSILON:
                continue
            base = parallelogram(origin, first, second)
            expected = quad_halfspace_solid(base, extrusion)
            actual, axis, complete = column_solid(base, extrusion)
            self.assertTrue(complete)
            self.assertEqual(expected, actual, (first, second, extrusion))
            self.assertTrue(contiguous_columns(actual, axis), (first, second, extrusion))
            self.assertTrue(boundary_is_plane_owned(actual, inward_planes(base, extrusion)), (first, second, extrusion))
            checked += 1

    def test_limit_never_returns_only_the_complete_base_face(self):
        base = parallelogram((.5, .5, .5), (20., 4., 1.), (-3., 12., 8.))
        extrusion = (12., 18., -9.)
        base_size = len(scan_face(base)[0])
        limited, _, complete = column_solid(base, extrusion, base_size)
        self.assertFalse(complete)
        self.assertEqual(base_size, len(limited))
        self.assertNotEqual(scan_face(base)[0], limited)


if __name__ == "__main__":
    unittest.main()
