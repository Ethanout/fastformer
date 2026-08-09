package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GenerationLimitExceededTest {
   @Test
   void lazyWitnessHasAnExactUniqueSizeEvenAtTheLargestIntBudget() {
      Set<BlockPos> small = GenerationLimitExceeded.witness(130);
      Set<BlockPos> largest = GenerationLimitExceeded.witness(Integer.MAX_VALUE);

      assertTrue(GenerationLimitExceeded.is(small));
      assertEquals(130, small.size());
      assertEquals(130, new HashSet<>(small).size());
      assertEquals(Integer.MAX_VALUE, largest.size());
      assertTrue(largest.contains(new BlockPos(-33_554_432, 0, 0)));
      assertTrue(largest.contains(new BlockPos(33_554_430, 0, 31)));
   }

   @Test
   void translatedFaceNearIntMaximumDegradesBeforeReturningATypedWitness() {
      List<Vec3> face = translatedFixedFace((double)Integer.MAX_VALUE - 282.0);
      Set<BlockPos> full = PlanarFaceRasterizer.generate(face, 10_000);
      Set<BlockPos> limited = PlanarFaceRasterizer.generate(face, full.size() - 1);
      Set<BlockPos> exact = PlanarFaceRasterizer.generate(face, full.size());
      Set<BlockPos> fullOutline = PlanarFaceRasterizer.outline(face, 10_000, BlockGenerationObserver.NONE);
      Set<BlockPos> limitedOutline = PlanarFaceRasterizer.outline(
         face, fullOutline.size() - 1, BlockGenerationObserver.NONE
      );
      Set<BlockPos> exactOutline = PlanarFaceRasterizer.outline(
         face, fullOutline.size(), BlockGenerationObserver.NONE
      );

      assertTrue(full.size() > 1);
      assertFalse(GenerationLimitExceeded.is(limited));
      assertFalse(limited.isEmpty());
      assertTrue(limited.size() <= full.size() - 1);
      assertEquals(limited.size(), new HashSet<>(limited).size());
      assertFalse(GenerationLimitExceeded.is(exact));
      assertEquals(full, exact, "a complete face exactly at maxBlocks must remain ordinary geometry");
      assertTrue(GenerationLimitExceeded.is(limitedOutline));
      assertEquals(fullOutline.size() - 1, limitedOutline.size());
      assertFalse(GenerationLimitExceeded.is(exactOutline));
      assertEquals(fullOutline, exactOutline, "a complete outline exactly at maxBlocks must remain ordinary geometry");
   }

   @Test
   void interpolatedQuadReturnsTypedLimitAndExactGeometryAtItsOwnBudget() {
      List<Vec3> face = fixedFace();
      Set<BlockPos> quadFull = QuadFaceGenerator.generate(face, FillMode.SOLID, 10_000);
      Set<BlockPos> polygonFull = PolygonFaceGenerator.generate(face, FillMode.SOLID, 10_000);

      Set<BlockPos> quadLimited = QuadFaceGenerator.generate(face, FillMode.SOLID, 1);
      Set<BlockPos> quadExact = QuadFaceGenerator.generate(face, FillMode.SOLID, quadFull.size());
      Set<BlockPos> polygonExact = PolygonFaceGenerator.generate(face, FillMode.SOLID, polygonFull.size());

      assertTrue(GenerationLimitExceeded.is(quadLimited));
      assertEquals(1, quadLimited.size());
      assertFalse(GenerationLimitExceeded.is(quadExact));
      assertFalse(GenerationLimitExceeded.is(polygonExact));
      assertEquals(quadFull, quadExact);
      assertEquals(polygonFull, polygonExact);
   }

   @Test
   void degenerateTiltedBoxPropagatesTypedQuadLimitWhilePyramidKeepsGenericFallback() {
      List<Vec3> face = fixedFace();
      int faceSize = QuadFaceGenerator.generate(face, FillMode.SOLID, 10_000).size();
      int boxLimit = faceSize - 1;
      int pyramidLimit = PlanarFaceRasterizer.generate(face, 10_000).size() - 2;

      Set<BlockPos> pyramid = PyramidGenerator.generate(face, new Vec3(0.0, 12.0, 0.0), FillMode.SOLID, pyramidLimit);
      Set<BlockPos> degenerateBox = TiltedBoxGenerator.generate(face, Vec3.ZERO, FillMode.SOLID, boxLimit);

      assertFalse(GenerationLimitExceeded.is(pyramid));
      assertTrue(GenerationLimitExceeded.is(degenerateBox));
      assertFalse(pyramid.isEmpty());
      assertTrue(pyramid.size() <= pyramidLimit);
      assertEquals(boxLimit, degenerateBox.size());
   }

   @Test
   void completeTiltedBoxExactlyAtItsBudgetIsNeverMarkedAsExceeded() {
      List<Vec3> face = fixedFace();
      Vec3 extrusion = new Vec3(-2.0, -1.0, -1.0);

      for (FillMode mode : FillMode.values()) {
         Set<BlockPos> full = TiltedBoxGenerator.generate(face, extrusion, mode, 100_000);
         Set<BlockPos> exact = TiltedBoxGenerator.generate(face, extrusion, mode, full.size());

         assertFalse(GenerationLimitExceeded.is(exact), mode.toString());
         assertEquals(full, exact, mode + " changed at an exact complete budget");
      }
   }

   private static List<Vec3> fixedFace() {
      return translatedFixedFace(0.0);
   }

   private static List<Vec3> translatedFixedFace(double xOffset) {
      return List.of(
         new Vec3(282.5 + xOffset, 63.5, 127.5),
         new Vec3(279.5 + xOffset, 70.5, 131.5),
         new Vec3(273.5 + xOffset, 70.5, 126.5),
         new Vec3(276.5 + xOffset, 63.5, 122.5)
      );
   }
}
