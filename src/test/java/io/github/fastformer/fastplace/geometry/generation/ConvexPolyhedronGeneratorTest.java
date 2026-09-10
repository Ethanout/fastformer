package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ConvexPolyhedronGeneratorTest {
   private static final List<BlockPos> NEIGHBORS = List.of(
      new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
      new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
      new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
   );

   @Test
   void scanlineSpansMatchTheImplicitShapesAfterRotationAndScale() {
      for (int shapeVariant : new int[]{0, 1, 2, 3}) {
         PolyhedronParameters parameters = parameters(shapeVariant);
         Set<BlockPos> expected = bruteForce(parameters);
         Set<BlockPos> actual = ConvexPolyhedronGenerator.generate(parameters, FillMode.SOLID, 100000);

         assertEquals(expected, actual, "shape variant " + shapeVariant);
      }
   }

   @Test
   void hollowIsTheSixNeighborBoundaryAndEstimateCountsColumns() {
      PolyhedronParameters parameters = parameters(1);
      Set<BlockPos> solid = ConvexPolyhedronGenerator.generate(parameters, FillMode.SOLID, 100000);
      Set<BlockPos> hollow = ConvexPolyhedronGenerator.generate(parameters, FillMode.HOLLOW, 100000);
      PolyhedronGeometry.Bounds bounds = PolyhedronGeometry.bounds(parameters, true);
      long columns = (long)(bounds.maxX() - bounds.minX() + 1) * (long)(bounds.maxY() - bounds.minY() + 1);

      assertEquals(columns, ConvexPolyhedronGenerator.estimateScanCells(parameters));
      assertTrue(solid.containsAll(hollow));
      assertTrue(hollow.stream().allMatch(position ->
         NEIGHBORS.stream().anyMatch(offset -> !solid.contains(position.offset(offset)))
      ));
      assertTrue(solid.stream().filter(position ->
         NEIGHBORS.stream().anyMatch(offset -> !solid.contains(position.offset(offset)))
      ).allMatch(hollow::contains));
   }

   @Test
   void largeConvexShapeUsesLazyStorage() {
      PolyhedronParameters parameters = parameters(2);

      Set<BlockPos> blocks = ConvexPolyhedronGenerator.generate(parameters, FillMode.SOLID, 100_000);

      assertTrue(blocks.getClass().getSimpleName().contains("LazyBlockSet"));
      assertEquals(blocks.size(), new HashSet<>(blocks).size());
   }

   private static Set<BlockPos> bruteForce(PolyhedronParameters parameters) {
      PolyhedronGeometry.Bounds bounds = PolyhedronGeometry.bounds(parameters, true);
      HashSet<BlockPos> result = new HashSet<>();
      for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
         for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
               BlockPos position = new BlockPos(x, y, z);
               Vec3 local = PolyhedronGeometry.toLocal(Vec3.atCenterOf(position).subtract(parameters.center()), parameters);
               if (inside(local, parameters.radius(1.0), parameters.shapeVariant())) {
                  result.add(position);
               }
            }
         }
      }
      return Set.copyOf(result);
   }

   private static boolean inside(Vec3 local, double radius, int shapeVariant) {
      double x = Math.abs(local.x);
      double y = Math.abs(local.y);
      double z = Math.abs(local.z);
      return switch (shapeVariant) {
         case 0 -> local.x + local.y + local.z <= radius + 1.0E-9
            && local.x - local.y - local.z <= radius + 1.0E-9
            && -local.x + local.y - local.z <= radius + 1.0E-9
            && -local.x - local.y + local.z <= radius + 1.0E-9;
         case 1 -> x + y + z <= radius + 1.0E-9;
         case 2 -> Math.max(x, Math.max(y, z)) + 0.35 * (x + y + z) <= radius * 1.35 + 1.0E-9;
         default -> x + y + z + 0.3 * Math.max(x, Math.max(y, z)) <= radius * 1.8 + 1.0E-9;
      };
   }

   private static PolyhedronParameters parameters(int shapeVariant) {
      double angle = Math.toRadians(27.0);
      double cosine = Math.cos(angle);
      double sine = Math.sin(angle);
      return new PolyhedronParameters(
         new Vec3(2.5, 3.5, 4.5),
         new Vec3(7.5, 3.5, 4.5),
         shapeVariant,
         new double[]{cosine, 0.0, sine, 0.0, 1.0, 0.0, -sine, 0.0, cosine},
         new Vec3(1.25, 0.8, 1.1),
         new Vec3(1.0, 1.2, 0.9),
         false
      );
   }
}
