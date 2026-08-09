package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PolygonFaceGeneratorTest {
   @Test
   void preservesAConcaveNotch() {
      List<Vec3> vertices = List.of(
         point(0, 0),
         point(4, 0),
         point(4, 1),
         point(1, 1),
         point(1, 4),
         point(0, 4)
      );

      Set<BlockPos> result = PolygonFaceGenerator.generate(vertices, FillMode.SOLID, 1000);
      Set<BlockPos> outline = PlanarFaceGeometry.outline(vertices, 1000);

      assertTrue(result.containsAll(outline));
      assertTrue(result.contains(new BlockPos(3, 0, 0)));
      assertTrue(result.contains(new BlockPos(0, 0, 3)));
      assertFalse(result.contains(new BlockPos(3, 0, 3)));
      assertTrue(result.stream().allMatch(position -> position.getY() == 0));
   }

   @Test
   void tiltedConcaveFaceKeepsTheNotchInsideItsOutline() {
      List<Vec3> vertices = List.of(
         tiltedPoint(0, 0),
         tiltedPoint(4, 0),
         tiltedPoint(4, 1),
         tiltedPoint(1, 1),
         tiltedPoint(1, 4),
         tiltedPoint(0, 4)
      );

      Set<BlockPos> result = PolygonFaceGenerator.generate(vertices, FillMode.SOLID, 1000);
      Set<BlockPos> outline = PlanarFaceGeometry.outline(vertices, 1000);

      assertTrue(result.containsAll(outline));
      assertTrue(result.stream().anyMatch(position -> position.getX() >= 3 && position.getY() <= 1));
      assertTrue(result.stream().anyMatch(position -> position.getX() <= 1 && position.getY() >= 3));
      assertFalse(result.stream().anyMatch(position -> position.getX() >= 3 && position.getY() >= 3));
   }

   private static Vec3 point(int x, int z) {
      return new Vec3(x + 0.5, 0.5, z + 0.5);
   }

   private static Vec3 tiltedPoint(int x, int alongSlope) {
      return new Vec3(x + 0.5, alongSlope + 0.5, alongSlope + 0.5);
   }
}
