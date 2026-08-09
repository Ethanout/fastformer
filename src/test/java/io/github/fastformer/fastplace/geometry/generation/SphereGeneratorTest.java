package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.FillMode;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SphereGeneratorTest {
   private static final List<BlockPos> NEIGHBORS = List.of(
      new BlockPos(1, 0, 0),
      new BlockPos(-1, 0, 0),
      new BlockPos(0, 1, 0),
      new BlockPos(0, -1, 0),
      new BlockPos(0, 0, 1),
      new BlockPos(0, 0, -1)
   );

   @Test
   void hollowSphereIsTheSixNeighborBoundaryOfTheSolid() {
      Vec3 center = new Vec3(0.5, 0.5, 0.5);
      PolyhedronParameters parameters = new PolyhedronParameters(
         center,
         center.add(3.0, 0.0, 0.0),
         5,
         null
      );

      Set<BlockPos> solid = SphereGenerator.generate(parameters, FillMode.SOLID, 10000);
      Set<BlockPos> hollow = SphereGenerator.generate(parameters, FillMode.HOLLOW, 10000);

      assertFalse(solid.isEmpty());
      assertTrue(solid.containsAll(hollow));
      assertTrue(hollow.size() < solid.size());
      assertTrue(hollow.stream().allMatch(position ->
         NEIGHBORS.stream().anyMatch(offset -> !solid.contains(position.offset(offset)))
      ));
      assertTrue(solid.stream().allMatch(position -> Vec3.atCenterOf(position).distanceToSqr(center) <= 9.0 + 1.0E-9));
   }

   @Test
   void rotatedNonUniformSphereSpanMatchesBruteForce() {
      Vec3 center = new Vec3(2.5, 3.5, 4.5);
      double angle = Math.toRadians(31.0);
      double cosine = Math.cos(angle);
      double sine = Math.sin(angle);
      PolyhedronParameters parameters = new PolyhedronParameters(
         center,
         center.add(5.0, 0.0, 0.0),
         5,
         new double[]{cosine, -sine, 0.0, sine, cosine, 0.0, 0.0, 0.0, 1.0},
         new Vec3(1.4, 0.75, 1.1),
         new Vec3(0.9, 1.2, 1.0),
         false
      );
      PolyhedronGeometry.Bounds bounds = PolyhedronGeometry.bounds(parameters, false);
      HashSet<BlockPos> expected = new HashSet<>();
      for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
         for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
               BlockPos position = new BlockPos(x, y, z);
               Vec3 local = PolyhedronGeometry.toLocal(Vec3.atCenterOf(position).subtract(center), parameters);
               if (local.lengthSqr() <= 25.0 + 1.0E-9) {
                  expected.add(position);
               }
            }
         }
      }

      Set<BlockPos> actual = SphereGenerator.generate(parameters, FillMode.SOLID, 100000);
      long columns = (long)(bounds.maxX() - bounds.minX() + 1) * (long)(bounds.maxY() - bounds.minY() + 1);

      assertEquals(expected, actual);
      assertEquals(columns, SphereGenerator.estimateScanCells(parameters));
   }
}
