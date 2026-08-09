package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class PolygonFaceGenerator {
   private PolygonFaceGenerator() {
   }

   public static Set<BlockPos> generateFromBlockPoints(List<BlockPos> points, FillMode fillMode, int maxBlocks) {
      return generateFromBlockPoints(points, fillMode, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generateFromBlockPoints(
      List<BlockPos> points, FillMode fillMode, int maxBlocks, BlockGenerationObserver observer
   ) {
      if (points.size() < 3) {
         return points.size() == 2
            ? LineGenerator.generate(points.getFirst(), points.get(1), maxBlocks, observer)
            : observedCopy(points, observer);
      }
      return generate(points.stream().map(Vec3::atCenterOf).toList(), fillMode, maxBlocks, observer);
   }

   public static Set<BlockPos> generate(List<Vec3> vertices, FillMode fillMode, int maxBlocks) {
      return generate(vertices, fillMode, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generate(List<Vec3> vertices, FillMode fillMode, int maxBlocks, BlockGenerationObserver observer) {
      if (vertices.size() < 3 || maxBlocks <= 0) {
         return Set.of();
      }
      if (fillMode == FillMode.OUTLINE) {
         return PlanarFaceRasterizer.outline(vertices, maxBlocks, observer);
      }
      Set<BlockPos> result = PlanarFaceRasterizer.generate(vertices, maxBlocks, observer);
      if (!result.isEmpty()) {
         return result;
      }
      Set<BlockPos> outline = PlanarFaceRasterizer.outline(vertices, maxBlocks, observer);
      if (GenerationLimitExceeded.is(outline)) {
         return outline;
      }
      Set<BlockPos> fallback = new ObservedBlockSet(observer);
      fallback.addAll(outline);
      return fallback;
   }

   private static Set<BlockPos> observedCopy(List<BlockPos> points, BlockGenerationObserver observer) {
      Set<BlockPos> result = new ObservedBlockSet(observer);
      result.addAll(points);
      return result;
   }
}
