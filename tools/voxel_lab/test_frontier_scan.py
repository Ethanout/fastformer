from __future__ import annotations

import itertools
import unittest

from frontier_scan import (
    logical_slice_bridge_advance,
    recursive_pi_advance,
    reverse_sweep,
    scan_parallelogram,
    swap_axes,
)
from planar_faces import bresenham_path_2d, parallelogram
from worker_surfaces import expected_projected_face, owned_edge_internal_l_pixels


def expected_face(
    origin: tuple[int, int],
    span: tuple[int, int],
    sweep: tuple[int, int],
) -> set[tuple[int, int]]:
    vertices = parallelogram(
        (origin[0] + 0.5, origin[1] + 0.5, 0.5),
        (float(span[0]), float(span[1]), 0.0),
        (float(sweep[0]), float(sweep[1]), 0.0),
    )
    return expected_projected_face(vertices)


def scan(
    span: tuple[int, int],
    sweep: tuple[int, int],
    advance=recursive_pi_advance,
):
    return scan_parallelogram((0, 0), span, sweep, advance)


class FrontierInterpretationTest(unittest.TestCase):
    def test_minimal_recursive_pi_point_set(self) -> None:
        # P0={(0,0),(1,0)}, frontier+x={(2,0)}, P0+y={(0,1),(1,1)}.
        result = scan((1, 0), (1, 1), recursive_pi_advance)
        self.assertEqual(
            {(0, 0), (1, 0), (2, 0), (0, 1), (1, 1)},
            set(result.cells),
        )

    def test_minimal_logical_slice_bridge_point_set(self) -> None:
        # The logical line reaches {(1,1),(2,1)}; {(2,0)} is emitted only as
        # the bridge for this step and is not recursively translated later.
        result = scan((1, 0), (1, 1), logical_slice_bridge_advance)
        self.assertEqual(
            {(0, 0), (1, 0), (2, 0), (1, 1), (2, 1)},
            set(result.cells),
        )

    def test_one_step_slanted_pi_makes_the_same_ambiguity_visible(self) -> None:
        recursive = scan((2, 1), (1, 1), recursive_pi_advance)
        bridged = scan((2, 1), (1, 1), logical_slice_bridge_advance)
        self.assertEqual(
            {(0, 1), (1, 0), (1, 2), (2, 2), (3, 1)},
            set(recursive.emitted_layers[1]),
        )
        self.assertEqual(
            {(1, 0), (1, 1), (2, 2), (3, 1), (3, 2)},
            set(bridged.emitted_layers[1]),
        )
        self.assertEqual({(3, 2)}, set(recursive.boundary - recursive.cells))
        self.assertFalse(bridged.boundary - bridged.cells)

    def test_literal_recursive_reading_loses_the_terminal_owned_edge(self) -> None:
        result = scan((1, 0), (1, 1), recursive_pi_advance)
        expected = expected_face((0, 0), (1, 0), (1, 1))
        self.assertEqual({(2, 1)}, expected - result.cells)
        self.assertEqual({(2, 1)}, set(result.boundary - result.cells))

    def test_logical_slice_bridge_closes_projected_holes(self) -> None:
        vectors = tuple(
            vector
            for vector in itertools.product(range(-3, 4), repeat=2)
            if vector != (0, 0)
        )
        checked = 0
        for span in vectors:
            for sweep in vectors:
                if span[0] * sweep[1] - span[1] * sweep[0] == 0:
                    continue
                result = scan(span, sweep, logical_slice_bridge_advance)
                self.assertFalse(
                    expected_face((0, 0), span, sweep) - result.cells,
                    (span, sweep, result.cells),
                )
                checked += 1
        self.assertGreater(checked, 1_000)

    def test_owned_geometry_edges_still_have_no_internal_l(self) -> None:
        result = scan((4, 1), (2, 5), logical_slice_bridge_advance)
        self.assertTrue(result.boundary <= result.cells)
        for edge in result.owned_edges:
            self.assertFalse(owned_edge_internal_l_pixels(edge), edge)

    def test_bridge_side_is_not_reversal_invariant(self) -> None:
        forward = scan((1, 0), (1, 1), logical_slice_bridge_advance)
        reverse_arguments = reverse_sweep((0, 0), (1, 0), (1, 1))
        backward = scan_parallelogram(
            *reverse_arguments,
            advance=logical_slice_bridge_advance,
        )
        self.assertEqual({(2, 0)}, set(forward.cells - backward.cells))
        self.assertEqual({(0, 1)}, set(backward.cells - forward.cells))

    def test_equal_axis_tie_is_not_axis_permutation_invariant(self) -> None:
        original = scan((1, 0), (1, 1), logical_slice_bridge_advance)
        swapped = scan_parallelogram(
            (0, 0),
            swap_axes((1, 0)),
            swap_axes((1, 1)),
            advance=logical_slice_bridge_advance,
        )
        swapped_back = {swap_axes(pixel) for pixel in swapped.cells}
        self.assertEqual({(2, 0)}, set(original.cells - swapped_back))
        self.assertEqual({(0, 1)}, swapped_back - original.cells)

    def test_current_line_tie_break_also_has_an_axis_permutation_witness(self) -> None:
        original = set(bresenham_path_2d((0, 0), (-2, 1)))
        swapped_back = {
            swap_axes(pixel)
            for pixel in bresenham_path_2d((0, 0), (1, -2))
        }
        self.assertEqual({(-1, 0)}, original - swapped_back)
        self.assertEqual({(-1, 1)}, swapped_back - original)


if __name__ == "__main__":
    unittest.main()
