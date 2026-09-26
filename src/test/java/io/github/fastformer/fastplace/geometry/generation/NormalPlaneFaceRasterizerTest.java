package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.geometry.FillMode;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class NormalPlaneFaceRasterizerTest {
   @Test
   void standaloneFaceUsesOneGradientPhase() {
      List<Vec3> vertices = List.of(
         new Vec3(0.5, 0.5, 0.5), new Vec3(30.5, 0.5, 6.5),
         new Vec3(30.5, 30.5, 21.5), new Vec3(0.5, 30.5, 15.5)
      );
      Set<BlockPos> face = generate(vertices, FillMode.SOLID);
      assertFalse(face.isEmpty());
      for (int x = 1; x < 30; x++) {
         for (int y = 1; y < 30; y++) {
            int px = x;
            int py = y;
            List<BlockPos> column = face.stream().filter(p -> p.getX() == px && p.getY() == py).toList();
            assertEquals(1, column.size(), "each projected column must have one height");
            assertTrue(Math.abs(column.getFirst().getZ() - (0.2 * x + 0.5 * y)) <= 0.50000001,
               "height must follow the plane rather than separate edge steps");
         }
      }
   }

   @Test
   void outlineKeepsAuthoredEdgesAndExactOppositeTranslations() {
      BlockPos origin = new BlockPos(3, 7, -4);
      BlockPos first = new BlockPos(23, 4, 7);
      BlockPos second = new BlockPos(-5, 19, 11);
      List<Vec3> vertices = List.of(origin, origin.offset(first), origin.offset(first).offset(second), origin.offset(second))
         .stream().map(Vec3::atCenterOf).toList();
      Set<BlockPos> expected = new java.util.HashSet<>();
      for (BlockPos p : LineGenerator.path(origin, origin.offset(first), LineTieBias.DEFAULT)) {
         expected.add(p);
         expected.add(p.offset(second));
      }
      for (BlockPos p : LineGenerator.path(origin, origin.offset(second), LineTieBias.DEFAULT)) {
         expected.add(p);
         expected.add(p.offset(first));
      }
      assertEquals(expected, generate(vertices, FillMode.OUTLINE));
      Vec3 extrusion = new Vec3(7, 13, -3);
      assertEquals(
         TiltedBoxGenerator.generate(vertices, extrusion, FillMode.OUTLINE, 10000,
            BlockGenerationObserver.NONE, LineTieBias.DEFAULT, FaceRasterizationMode.POINT_SWEEP),
         TiltedBoxGenerator.generate(vertices, extrusion, FillMode.OUTLINE, 10000,
            BlockGenerationObserver.NONE, LineTieBias.DEFAULT, FaceRasterizationMode.NORMAL_PLANE_EXPERIMENTAL)
      );
   }

   private static Set<BlockPos> generate(List<Vec3> vertices, FillMode fill) {
      return QuadFaceGenerator.generate(vertices, fill, 10000, BlockGenerationObserver.NONE,
         LineTieBias.DEFAULT, FaceRasterizationMode.NORMAL_PLANE_EXPERIMENTAL);
   }
}
