from __future__ import annotations

import unittest

from closed_volumes import (
    current_layered_cylinder,
    current_layered_prism,
    frustum_solid,
    prism_hollow,
    prism_solid,
    pyramid_hollow,
    pyramid_solid,
    quad_halfspace_hollow,
    quad_halfspace_outline,
    quad_halfspace_solid,
    quad_prism_shell,
    shell_flood_solid,
    six_neighbor_boundary,
)
from planar_faces import add, containing, parallelogram


def is_26_connected(voxels: set[tuple[int, int, int]]) -> bool:
    if not voxels:
        return True
    visited = {next(iter(voxels))}
    queue = list(visited)
    while queue:
        voxel = queue.pop()
        for x in range(voxel[0] - 1, voxel[0] + 2):
            for y in range(voxel[1] - 1, voxel[1] + 2):
                for z in range(voxel[2] - 1, voxel[2] + 2):
                    neighbor = x, y, z
                    if neighbor in voxels and neighbor not in visited:
                        visited.add(neighbor)
                        queue.append(neighbor)
    return visited == voxels


class ClosedVolumeTest(unittest.TestCase):
    def test_tilted_cylinder_is_one_continuous_volume_not_discrete_layers(self) -> None:
        center = (3.5, 3.5, 3.5)
        normal = (0.0, -1.0, 1.0)
        height = 7
        radius = 27.0**0.5

        layered = current_layered_cylinder(center, normal, height, radius)
        solid = frustum_solid(center, normal, height, radius)

        self.assertTrue(solid)
        self.assertGreater(len(solid - layered), 20)
        self.assertLess(
            len(six_neighbor_boundary(solid)) / len(solid),
            len(six_neighbor_boundary(layered)) / len(layered),
        )

    def test_axis_aligned_prism_has_exact_volume_and_surface(self) -> None:
        base = (
            (0.5, 0.5, 0.5),
            (4.5, 0.5, 0.5),
            (4.5, 0.5, 3.5),
            (0.5, 0.5, 3.5),
        )
        solid = prism_solid(base, (0.0, 4.0, 0.0))
        hollow = prism_hollow(base, (0.0, 4.0, 0.0))
        self.assertEqual(100, len(solid))
        self.assertEqual(six_neighbor_boundary(solid), hollow)

    def test_tilted_prism_surface_is_canonical_not_exposed_layer_stack(self) -> None:
        base = (
            (0.5, 0.5, 0.5),
            (-2.5, -2.5, -2.5),
            (-5.5, -5.5, 0.5),
            (-2.5, -2.5, 3.5),
        )
        extrusion = (-4.0, 4.0, 0.0)
        old = current_layered_prism(base, extrusion)
        solid = prism_solid(base, extrusion)
        hollow = prism_hollow(base, extrusion)

        self.assertEqual(six_neighbor_boundary(solid), hollow)
        self.assertGreater(len(six_neighbor_boundary(old) ^ hollow), 20)
        self.assertGreater(len(six_neighbor_boundary(old)) / len(old), len(hollow) / len(solid))

    def test_tilted_box_halfspaces_define_one_canonical_solid_and_surface(self) -> None:
        base = parallelogram(
            (0.5, 0.5, 0.5),
            (4.0, 0.0, 4.0),
            (-2.0, 4.0, 2.0),
        )
        extrusion = (-4.0, -4.0, 4.0)

        solid = quad_halfspace_solid(base, extrusion)
        hollow = quad_halfspace_hollow(base, extrusion)

        self.assertTrue(solid)
        self.assertEqual(six_neighbor_boundary(solid), hollow)

    def test_tilted_box_outline_stays_on_its_canonical_surface(self) -> None:
        base = parallelogram(
            (0.5, 0.5, 0.5),
            (3.0, 3.0, 0.0),
            (-1.0, 1.0, 4.0),
        )
        extrusion = (6.0, -6.0, 3.0)

        outline = quad_halfspace_outline(base, extrusion)
        hollow = quad_halfspace_hollow(base, extrusion)
        corners = {containing(point) for point in (*base, *(add(point, extrusion) for point in base))}

        self.assertTrue(outline)
        self.assertTrue(outline <= hollow)
        self.assertTrue(corners <= outline)
        self.assertTrue(is_26_connected(outline))

    def test_discrete_face_shell_can_leak_during_flood_fill(self) -> None:
        base = parallelogram(
            (0.5, 0.5, 0.5),
            (3.0, 3.0, 0.0),
            (-1.0, 1.0, 4.0),
        )
        extrusion = (6.0, -6.0, 3.0)

        shell = quad_prism_shell(base, extrusion)
        flood_solid = shell_flood_solid(shell)
        halfspace_solid = quad_halfspace_solid(base, extrusion)

        self.assertEqual(shell, flood_solid)
        self.assertGreater(len(halfspace_solid), len(shell))

    def test_concave_prism_keeps_the_notch_through_its_height(self) -> None:
        base = (
            (0.5, 0.5, 0.5),
            (4.5, 0.5, 0.5),
            (4.5, 0.5, 1.5),
            (1.5, 0.5, 1.5),
            (1.5, 0.5, 4.5),
            (0.5, 0.5, 4.5),
        )
        solid = prism_solid(base, (1.0, 4.0, 0.0))
        for y in range(5):
            self.assertNotIn((3, y, 3), solid)

    def test_tilted_pyramid_hollow_is_exactly_solid_boundary(self) -> None:
        base = (
            (0.5, 0.5, 0.5),
            (5.5, 1.5, -1.5),
            (3.5, 5.5, 1.5),
            (-1.5, 4.5, 3.5),
        )
        extrusion = (3.0, -4.0, 7.0)
        solid = pyramid_solid(base, extrusion)
        hollow = pyramid_hollow(base, extrusion)
        self.assertTrue(solid)
        self.assertEqual(six_neighbor_boundary(solid), hollow)

    def test_output_limits_are_strict(self) -> None:
        base = (
            (0.5, 0.5, 0.5),
            (20.5, 0.5, 0.5),
            (20.5, 0.5, 20.5),
            (0.5, 0.5, 20.5),
        )
        self.assertEqual(31, len(prism_solid(base, (3.0, 20.0, 4.0), 31)))
        self.assertEqual(29, len(prism_hollow(base, (3.0, 20.0, 4.0), 29)))
        self.assertEqual(23, len(pyramid_solid(base, (0.0, 20.0, 0.0), 23)))
        self.assertEqual(19, len(pyramid_hollow(base, (0.0, 20.0, 0.0), 19)))


if __name__ == "__main__":
    unittest.main()
