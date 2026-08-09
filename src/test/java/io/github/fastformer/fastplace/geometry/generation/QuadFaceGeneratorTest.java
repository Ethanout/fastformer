package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.List;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuadFaceGeneratorTest {
   @Test
   void fillsBetweenCorrespondingEdgesWithoutLeavingTheOutlineBounds() {
      List<Vec3> vertices = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 3.5),
         new Vec3(0.5, 0.5, 3.5)
      );

      Set<BlockPos> result = QuadFaceGenerator.generate(vertices, FillMode.SOLID, 1000);
      Set<BlockPos> outline = QuadFaceGenerator.generate(vertices, FillMode.OUTLINE, 1000);

      assertEquals(20, result.size());
      assertTrue(result.containsAll(outline));
      assertTrue(result.stream().allMatch(position ->
         position.getY() == 0
            && position.getX() >= 0 && position.getX() <= 4
            && position.getZ() >= 0 && position.getZ() <= 3
      ));
      for (int x = 0; x <= 4; x++) {
         assertTrue(result.contains(new BlockPos(x, 0, 0)));
      }
   }

   @Test
   void tiltedQuadKeepsItsOutlineAndSingleVoxelPlane() {
      List<Vec3> vertices = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(5.5, 0.5, 0.5),
         new Vec3(5.5, 4.5, 4.5),
         new Vec3(0.5, 4.5, 4.5)
      );

      Set<BlockPos> result = QuadFaceGenerator.generate(vertices, FillMode.SOLID, 1000);
      Set<BlockPos> outline = QuadFaceGenerator.generate(vertices, FillMode.OUTLINE, 1000);

      assertTrue(result.containsAll(outline));
      assertTrue(result.stream().allMatch(position -> Math.abs(position.getY() - position.getZ()) <= 1));
      assertTrue(result.stream().noneMatch(position -> position.getY() < -1 || position.getY() > 5));
   }

   @Test
   void rankedThreeDimensionalScanDoesNotLeaveCheckerboardHoles() {
      List<Vec3> vertices = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(-2.5, -2.5, -2.5),
         new Vec3(-5.5, -5.5, 0.5),
         new Vec3(-2.5, -2.5, 3.5)
      );

      Set<BlockPos> result = QuadFaceGenerator.generate(vertices, FillMode.SOLID, 1000);
      Set<String> projectedColumns = result.stream()
         .map(position -> position.getY() + "," + position.getZ())
         .collect(Collectors.toSet());
      assertEquals(25, projectedColumns.size(), "surface bridges must not create checkerboard projection spikes");
      ProjectedBresenhamFace.Raster raster = ProjectedBresenhamFace.raster(vertices);
      assertTrue(result.containsAll(raster.outline()));
      assertTrue(raster.logicalSingleLayer());
      assertEquals(1, raster.maximumThickness());
      assertTrue(is26Connected(result), "single-layer diagonal seams must remain 26-connected");
      assertTrue(result.stream().allMatch(position ->
         position.getX() >= -6 && position.getX() <= 0
            && position.getY() >= -6 && position.getY() <= 0
            && position.getZ() >= -3 && position.getZ() <= 3
      ));
   }

   private static boolean is26Connected(Set<BlockPos> blocks) {
      if (blocks.isEmpty()) {
         return true;
      }
      HashSet<BlockPos> visited = new HashSet<>();
      ArrayDeque<BlockPos> open = new ArrayDeque<>();
      open.add(blocks.iterator().next());
      while (!open.isEmpty()) {
         BlockPos current = open.removeFirst();
         if (!visited.add(current)) {
            continue;
         }
         for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
               for (int z = -1; z <= 1; z++) {
                  if (x == 0 && y == 0 && z == 0) {
                     continue;
                  }
                  BlockPos neighbor = current.offset(x, y, z);
                  if (blocks.contains(neighbor) && !visited.contains(neighbor)) {
                     open.addLast(neighbor);
                  }
               }
            }
         }
      }
      return visited.size() == blocks.size();
   }
}
