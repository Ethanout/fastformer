import itertools
import unittest

from bresenham_scan_lab import canonical_frame
from minimal_surface_bridge import bridge_face, cracks, is_six_connected, local_columns, maximum_thickness
from planar_faces import EPSILON, cross, length_squared, parallelogram


class MinimalSurfaceBridgeTest(unittest.TestCase):
    def test_small_slopes_are_closed_without_recursive_thickening(self):
        origin = (.5, .5, .5)
        vectors = [tuple(map(float, vector)) for vector in itertools.product(range(-2, 3), repeat=3)
                   if vector != (0, 0, 0)]
        checked = bridged = 0
        for first in vectors:
            for second in vectors:
                if length_squared(cross(first, second)) <= EPSILON:
                    continue
                vertices = parallelogram(origin, first, second)
                filled, logical, bridges, frame = bridge_face(vertices)
                columns = local_columns(logical, frame)
                projected = set(columns)
                self.assertFalse(any(
                    (u + du, v + dv) in projected
                    and (u + du, v) not in projected
                    and (u, v + dv) not in projected
                    for u, v in projected for du, dv in ((1, 1), (1, -1))
                ), vertices)
                self.assertFalse(cracks(columns) and not bridges, vertices)
                self.assertTrue(is_six_connected(filled), vertices)
                self.assertLessEqual(maximum_thickness(filled, frame), 2, vertices)
                self.assertTrue({frame.project_voxel(point) for point in bridges} <= set(columns), vertices)
                self.assertLessEqual(len(bridges), len(cracks(columns)), vertices)
                bridged += bool(bridges); checked += 1
        self.assertGreater(checked, 10_000)
        self.assertGreater(bridged, 1_000)

    def test_screenshot_style_slope_gets_risers_instead_of_open_diagonals(self):
        vertices = parallelogram((.5, .5, .5), (12., 5., 3.), (5., 10., -2.))
        filled, logical, bridges, frame = bridge_face(vertices)
        self.assertTrue(cracks(local_columns(logical, frame)))
        self.assertTrue(bridges)
        self.assertTrue(is_six_connected(filled))
        self.assertEqual(2, maximum_thickness(filled, frame))


if __name__ == "__main__":
    unittest.main()
