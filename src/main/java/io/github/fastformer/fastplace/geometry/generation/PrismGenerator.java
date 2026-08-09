package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.FaceRasterizationMode;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class PrismGenerator {
   private static final int[][] SIX_NEIGHBORS = {
      {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
   };

   private PrismGenerator() {
   }

   public static Set<BlockPos> generateQuad(List<Vec3> base, Vec3 extrusion, FillMode fillMode, int maxBlocks) {
      return generateQuad(base, extrusion, fillMode, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generateQuad(
      List<Vec3> base, Vec3 extrusion, FillMode fillMode, int maxBlocks, BlockGenerationObserver observer
   ) {
      return generateQuad(base, extrusion, fillMode, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   public static Set<BlockPos> generateQuad(
      List<Vec3> base,
      Vec3 extrusion,
      FillMode fillMode,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return generateQuad(
         base, extrusion, fillMode, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP
      );
   }

   public static Set<BlockPos> generateQuad(
      List<Vec3> base,
      Vec3 extrusion,
      FillMode fillMode,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode
   ) {
      return TiltedBoxGenerator.generate(
         base, extrusion, fillMode, maxBlocks, observer, tieBias, rasterizationMode
      );
   }

   public static Set<BlockPos> generatePolygon(List<Vec3> base, Vec3 extrusion, FillMode fillMode, int maxBlocks) {
      return generatePolygon(base, extrusion, fillMode, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generatePolygon(
      List<Vec3> base, Vec3 extrusion, FillMode fillMode, int maxBlocks, BlockGenerationObserver observer
   ) {
      return generate(base, extrusion, fillMode, maxBlocks, observer);
   }

   private static Set<BlockPos> generate(
      List<Vec3> base, Vec3 extrusion, FillMode fillMode, int maxBlocks, BlockGenerationObserver observer
   ) {
      List<BlockPos> offsets = LineGenerator.offsets(extrusion, maxBlocks);
      if (offsets.isEmpty()) {
         return Set.of();
      }
      if (fillMode == FillMode.OUTLINE) {
         return outline(base, extrusion, maxBlocks, observer);
      }
      Set<BlockPos> baseFilled = PolygonFaceGenerator.generate(
         base, FillMode.SOLID, Integer.MAX_VALUE, BlockGenerationObserver.NONE
      );
      Set<BlockPos> baseBoundary = PlanarFaceRasterizer.outline(base, Integer.MAX_VALUE, BlockGenerationObserver.NONE);
      if (GenerationLimitExceeded.is(baseFilled) || GenerationLimitExceeded.is(baseBoundary)) {
         return GenerationLimitExceeded.witness(maxBlocks, observer);
      }
      BresenhamVolumeSweep.Result generated = BresenhamVolumeSweep.generate(
         baseFilled,
         baseBoundary,
         extrusion,
         fillMode == FillMode.SOLID ? maxBlocks : Integer.MAX_VALUE,
         fillMode == FillMode.SOLID ? observer : BlockGenerationObserver.NONE
      );
      if (!generated.complete()) {
         return GenerationLimitExceeded.witness(maxBlocks, observer);
      }
      if (fillMode == FillMode.SOLID) {
         return generated.blocks();
      }
      return hollow(generated.blocks(), maxBlocks, observer);
   }

   private static Set<BlockPos> outline(
      List<Vec3> base, Vec3 extrusion, int maxBlocks, BlockGenerationObserver observer
   ) {
      List<BlockPos> offsets = LineGenerator.offsets(extrusion, maxBlocks);
      if (offsets.isEmpty()) {
         return Set.of();
      }
      Set<BlockPos> result = new ObservedBlockSet(observer);
      Set<BlockPos> baseOutline = PlanarFaceRasterizer.outline(base, maxBlocks, BlockGenerationObserver.NONE);
      if (GenerationLimitExceeded.is(baseOutline)) {
         return GenerationLimitExceeded.witness(maxBlocks, observer);
      }
      addTranslated(result, baseOutline, offsets.getFirst(), maxBlocks);
      addTranslated(result, baseOutline, offsets.getLast(), maxBlocks);
      for (Vec3 vertex : base) {
         LineGenerator.add(result, vertex, vertex.add(extrusion), maxBlocks);
      }
      return result;
   }

   private static Set<BlockPos> hollow(Set<BlockPos> solid, int maxBlocks, BlockGenerationObserver observer) {
      Set<BlockPos> result = new ObservedBlockSet(observer);
      for (BlockPos position : solid) {
         if (isBoundary(position, solid)) {
            result.add(position);
         }
         if (result.size() >= maxBlocks) {
            return result;
         }
      }
      return result;
   }

   private static boolean isBoundary(BlockPos position, Set<BlockPos> solid) {
      for (int[] neighbor : SIX_NEIGHBORS) {
         if (!solid.contains(position.offset(neighbor[0], neighbor[1], neighbor[2]))) {
            return true;
         }
      }
      return false;
   }

   private static void addTranslated(Set<BlockPos> output, Set<BlockPos> source, BlockPos offset, int maxBlocks) {
      for (BlockPos position : source) {
         output.add(position.offset(offset));
         if (output.size() >= maxBlocks) {
            return;
         }
      }
   }

}
