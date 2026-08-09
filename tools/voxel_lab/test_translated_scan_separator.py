import itertools
import random
import unittest

from translated_scan_separator import (
    N4,
    add,
    build_model,
    cross,
    generate,
    nested_line,
)


def transform(point, order, signs):
    return tuple(signs[index] * point[order[index]] for index in range(3))


def wind(vertices, start, direction):
    return tuple(vertices[(start + direction * index) % 4] for index in range(4))


class TranslatedScanSeparatorTest(unittest.TestCase):
    FIXED = (
        (282, 63, 127),
        (279, 70, 131),
        (273, 70, 126),
        (276, 63, 122),
    )

    def assert_contract(self, result):
        metric = result.metrics
        self.assertTrue(self.four_connected(set(result.model.domain)))
        self.assertTrue(self.has_no_projected_hole(set(result.model.domain)))
        self.assertEqual(0, metric.owned_missing)
        self.assertEqual(0, metric.projected_missing)
        self.assertEqual(0, metric.gap_columns)
        self.assertFalse(metric.air_leak)
        self.assertLessEqual(metric.maximum_interior_thickness, 2)
        self.assertEqual(0, metric.basin_cells)
        self.assertEqual(0, metric.strict_extrema)

    def test_coordinate_partial_and_fully_tilted_faces(self):
        cases = (
            ((5, 0, 0), (0, 4, 0)),
            ((6, 2, 0), (1, 4, 0)),
            ((9, 6, 2), (9, -3, 1)),
        )
        for first, second in cases:
            vertices = ((0, 0, 0), first, add(first, second), second)
            with self.subTest(first=first, second=second):
                self.assert_contract(generate(vertices))

    def test_fixed_visual_face_has_only_three_boundary_double_columns(self):
        result = generate(self.FIXED)
        self.assert_contract(result)
        self.assertEqual("translated-cut", result.method)
        self.assertEqual(59, result.metrics.blocks)
        self.assertEqual(56, result.metrics.columns)
        self.assertEqual(2, result.metrics.maximum_thickness)
        self.assertEqual(1, result.metrics.maximum_interior_thickness)
        self.assertEqual(3, result.metrics.support)
        self.assertLessEqual(result.metrics.longest_extra_contour, 2)
        self.assertEqual(
            {(274, 69, 126), (275, 67, 125), (281, 64, 127)},
            set(result.support),
        )

    def test_diagonal_owned_outlier_gets_a_one_shot_inward_bridge(self):
        vertices = ((-1, 1, -2), (-3, 3, -6), (2, -2, 0), (4, -4, 4))
        result = generate(vertices)
        self.assert_contract(result)
        self.assertIn((3, -6), result.model.domain)

    def test_missing_projected_cells_use_immutable_cross_samples(self):
        origin = (-1, 1, 2)
        first, second = (4, -3, 3), (-1, -1, 2)
        vertices = (origin, add(origin, first), add(add(origin, first), second), add(origin, second))
        model = build_model(vertices)
        raw_shadow = {model.project(point) for point in model.raw}
        holes = set(model.domain) - raw_shadow
        self.assertEqual(3, len(holes))
        for pixel in holes:
            self.assertTrue(all((pixel[0] + du, pixel[1] + dv) in raw_shadow for du, dv in N4))
        self.assertEqual(1, model.interpolation_radius)
        self.assert_contract(generate(vertices))

    def test_fixed_face_is_independent_of_start_and_winding(self):
        expected = generate(self.FIXED).blocks
        for start in range(4):
            for direction in (1, -1):
                self.assertEqual(expected, generate(wind(self.FIXED, start, direction)).blocks)

    def test_all_axis_and_sign_transforms_for_a_tie_free_tilted_face(self):
        origin, first, second = (2, -1, 3), (9, 6, 2), (9, -3, 1)
        vertices = (origin, add(origin, first), add(add(origin, first), second), add(origin, second))
        expected = generate(vertices).blocks
        for order in itertools.permutations(range(3)):
            for signs in itertools.product((-1, 1), repeat=3):
                transformed = tuple(transform(point, order, signs) for point in vertices)
                self.assertEqual(
                    {transform(point, order, signs) for point in expected},
                    set(generate(transformed).blocks),
                    (order, signs),
                )

    def test_exact_half_tie_exposes_the_known_transform_conflict(self):
        first, second = (-1, -1, 0), (1, 0, 1)
        path = set(nested_line(first, second))
        transformed = {(-x, -z, -y) for x, y, z in path}
        # T swaps the endpoints, but the current thin lexicographic tie answer
        # is not fixed by T.  This is the explicit impossibility witness.
        self.assertEqual({first, second}, {(-x, -z, -y) for x, y, z in (first, second)})
        self.assertNotEqual(path, transformed)
        self.assertEqual(path, set(nested_line(second, first)))

    def test_fixed_face_has_exactly_the_expected_36_tie_transform_failures(self):
        expected = generate(self.FIXED).blocks
        failures = 0
        for order in itertools.permutations(range(3)):
            for signs in itertools.product((-1, 1), repeat=3):
                transformed = tuple(transform(point, order, signs) for point in self.FIXED)
                actual = generate(transformed).blocks
                failures += actual != {transform(point, order, signs) for point in expected}
        self.assertEqual(36, failures)

    def test_small_random_faces_and_immutable_cross_ray_interpolation(self):
        randomizer = random.Random(0x5EEDFACE)
        checked = hole_faces = 0
        while checked < 256:
            origin = tuple(randomizer.randint(-2, 2) for _ in range(3))
            first = tuple(randomizer.randint(-6, 6) for _ in range(3))
            second = tuple(randomizer.randint(-6, 6) for _ in range(3))
            if first == (0, 0, 0) or second == (0, 0, 0) or cross(first, second) == (0, 0, 0):
                continue
            vertices = (
                origin,
                add(origin, first),
                add(add(origin, first), second),
                add(origin, second),
            )
            model = build_model(vertices)
            raw_shadow = {model.project(point) for point in model.raw}
            holes = set(model.domain) - raw_shadow
            if holes:
                hole_faces += 1
                radius_limit = max(
                    max(pixel[0] for pixel in model.domain) - min(pixel[0] for pixel in model.domain) + 3,
                    max(pixel[1] for pixel in model.domain) - min(pixel[1] for pixel in model.domain) + 3,
                )
                for pixel in holes:
                    self.assertTrue(any(
                        (pixel[0] + du * radius, pixel[1] + dv * radius) in raw_shadow
                        for du, dv in N4 for radius in range(1, radius_limit + 1)
                    ))
                    self.assertTrue(model.preferred[pixel])
            self.assert_contract(generate(vertices))
            checked += 1
        self.assertGreater(hole_faces, 100)

    @staticmethod
    def four_connected(cells):
        if not cells:
            return True
        unseen = set(cells)
        open_cells = [unseen.pop()]
        while open_cells:
            pixel = open_cells.pop()
            for du, dv in N4:
                neighbor = pixel[0] + du, pixel[1] + dv
                if neighbor in unseen:
                    unseen.remove(neighbor)
                    open_cells.append(neighbor)
        return not unseen

    @staticmethod
    def has_no_projected_hole(cells):
        if not cells:
            return True
        minimum_u = min(pixel[0] for pixel in cells) - 1
        maximum_u = max(pixel[0] for pixel in cells) + 1
        minimum_v = min(pixel[1] for pixel in cells) - 1
        maximum_v = max(pixel[1] for pixel in cells) + 1
        exterior = {(minimum_u, minimum_v)}
        open_cells = list(exterior)
        while open_cells:
            pixel = open_cells.pop()
            for du, dv in N4:
                neighbor = pixel[0] + du, pixel[1] + dv
                if not (minimum_u <= neighbor[0] <= maximum_u
                        and minimum_v <= neighbor[1] <= maximum_v):
                    continue
                if neighbor in cells or neighbor in exterior:
                    continue
                exterior.add(neighbor)
                open_cells.append(neighbor)
        return all(
            (u, v) in cells or (u, v) in exterior
            for u in range(minimum_u, maximum_u + 1)
            for v in range(minimum_v, maximum_v + 1)
        )


if __name__ == "__main__":
    unittest.main()
