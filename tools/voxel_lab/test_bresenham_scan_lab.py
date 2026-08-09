import itertools
import unittest

from bresenham_scan_lab import (
    canonical_frame,
    projected_face,
    scan_face,
    signed_permutations,
    thickness,
    transform_point,
    transform_voxel,
)
from planar_faces import EPSILON, cross, face_normal, length_squared, parallelogram


class TwoStageFaceTest(unittest.TestCase):
    def test_exhaustive_small_faces_are_complete_single_layers_with_shared_outlines(self):
        origin = (.5, .5, .5)
        vectors = [tuple(map(float, vector)) for vector in itertools.product(range(-2, 3), repeat=3)
                   if vector != (0, 0, 0)]
        checked = 0
        for a in vectors:
            for b in vectors:
                if length_squared(cross(a, b)) <= EPSILON:
                    continue
                vertices = parallelogram(origin, a, b)
                face, edges, frame = scan_face(vertices)
                _, local_vertices = canonical_frame(vertices)
                expected, projected_edges = projected_face(local_vertices)
                actual = {frame.project_voxel(point) for point in face}
                outline = {frame.project_voxel(point) for edge in edges for point in edge}
                self.assertEqual(expected, actual, vertices)
                self.assertEqual({cell for edge in projected_edges for cell in edge}, outline, vertices)
                self.assertTrue({point for edge in edges for point in edge} <= face, vertices)
                self.assertTrue(all(count == 1 for count in thickness(face, frame).values()), vertices)
                self.assertEqual(face, scan_face(vertices[2:] + vertices[:2])[0], vertices)
                checked += 1
        self.assertGreater(checked, 10_000)

    def test_all_signed_axis_permutations_keep_the_raster_contract(self):
        vertices = parallelogram((.5, .5, .5), (7., 4., 2.), (-2., 6., 5.))
        normal = face_normal(vertices)
        self.assertEqual(1, sum(abs(value) == max(map(abs, normal)) for value in normal))
        expected_size = len(scan_face(vertices)[0])
        for order, signs in signed_permutations():
            transformed_vertices = tuple(transform_point(vertex, order, signs) for vertex in vertices)
            transformed, edges, frame = scan_face(transformed_vertices)
            _, local_vertices = canonical_frame(transformed_vertices)
            expected, projected_edges = projected_face(local_vertices)
            self.assertEqual(expected_size, len(transformed), (order, signs))
            self.assertEqual(expected, {frame.project_voxel(point) for point in transformed}, (order, signs))
            self.assertEqual(
                {cell for edge in projected_edges for cell in edge},
                {frame.project_voxel(point) for edge in edges for point in edge},
                (order, signs),
            )
            self.assertTrue(all(count == 1 for count in thickness(transformed, frame).values()), (order, signs))

    def test_reported_diagonal_case_has_no_hole_or_thick_column(self):
        vertices = parallelogram((.5, .5, .5), (-3., -3., -3.), (-3., 3., -3.))
        face, _, frame = scan_face(vertices)
        _, local_vertices = canonical_frame(vertices)
        expected, _ = projected_face(local_vertices)
        self.assertEqual(expected, {frame.project_voxel(point) for point in face})
        self.assertTrue(all(count == 1 for count in thickness(face, frame).values()))


if __name__ == "__main__":
    unittest.main()
