import itertools
import random
import unittest

from owned_face_support import local_symmetric_face, supported_owned_face
from planar_faces import EPSILON, cross, length_squared, parallelogram


def centers(points):
    return tuple(tuple(value + 0.5 for value in point) for point in points)


class OwnedFaceSupportTest(unittest.TestCase):
    def test_report_fixed_crater_face(self):
        vertices = centers(((282, 63, 127), (279, 70, 131), (273, 70, 126), (276, 63, 122)))
        current = local_symmetric_face(vertices)
        candidate = supported_owned_face(vertices)
        print("fixed current", current.metrics)
        print("fixed candidate", candidate.side, candidate.metrics)
        self.assertTrue(candidate.outline <= candidate.blocks)
        self.assertTrue(candidate.metrics.six_connected)
        self.assertEqual(0, candidate.metrics.upper_pits)
        self.assertEqual(0, candidate.metrics.lower_pits)
        self.assertEqual(0, candidate.metrics.upper_pits_all)
        self.assertEqual(0, candidate.metrics.lower_pits_all)
        self.assertEqual(0, candidate.metrics.upper_basin_cells)
        self.assertEqual(0, candidate.metrics.lower_basin_cells)
        self.assertLessEqual(candidate.metrics.maximum_interior_thickness, 2)
        self.assertEqual("normal", candidate.side)
        self.assertEqual(107, candidate.metrics.blocks)

    def test_random_small_faces_keep_owned_edges_and_local_thickness(self):
        randomizer = random.Random(0xFACE5EED)
        checked = 0
        for _ in range(1000):
            first = tuple(randomizer.randint(-4, 4) for _ in range(3))
            second = tuple(randomizer.randint(-4, 4) for _ in range(3))
            if first == (0, 0, 0) or second == (0, 0, 0):
                continue
            if length_squared(cross(first, second)) <= EPSILON:
                continue
            vertices = parallelogram((0.5, 0.5, 0.5), first, second)
            candidate = supported_owned_face(vertices)
            self.assertTrue(candidate.outline <= candidate.blocks, vertices)
            self.assertTrue(candidate.metrics.six_connected, vertices)
            self.assertLessEqual(candidate.metrics.maximum_interior_thickness, 2, vertices)
            self.assertEqual(0, candidate.metrics.upper_basin_cells, vertices)
            self.assertEqual(0, candidate.metrics.lower_basin_cells, vertices)
            checked += 1
        self.assertGreater(checked, 900)

    def test_small_exhaustive_vectors(self):
        vectors = [
            tuple(map(float, vector))
            for vector in itertools.product(range(-2, 3), repeat=3)
            if vector != (0, 0, 0)
        ]
        checked = 0
        for first in vectors:
            for second in vectors:
                if length_squared(cross(first, second)) <= EPSILON:
                    continue
                candidate = supported_owned_face(
                    parallelogram((0.5, 0.5, 0.5), first, second)
                )
                self.assertTrue(candidate.outline <= candidate.blocks, (first, second))
                self.assertTrue(candidate.metrics.six_connected, (first, second))
                self.assertLessEqual(candidate.metrics.maximum_interior_thickness, 2, (first, second))
                self.assertEqual(0, candidate.metrics.upper_basin_cells, (first, second))
                self.assertEqual(0, candidate.metrics.lower_basin_cells, (first, second))
                checked += 1
        self.assertGreater(checked, 10_000)


if __name__ == "__main__":
    unittest.main()
