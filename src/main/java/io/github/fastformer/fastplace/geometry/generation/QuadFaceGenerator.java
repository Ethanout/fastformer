package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.FaceRasterizationMode;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class QuadFaceGenerator {
   private QuadFaceGenerator() {
   }

   public static Set<BlockPos> generate(List<Vec3> vertices, FillMode fillMode, int maxBlocks) {
      return generate(vertices, fillMode, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generate(List<Vec3> vertices, FillMode fillMode, int maxBlocks, BlockGenerationObserver observer) {
      return generate(vertices, fillMode, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   public static Set<BlockPos> generate(
      List<Vec3> vertices,
      FillMode fillMode,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return generate(
         vertices, fillMode, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP
      );
   }

   public static Set<BlockPos> generate(
      List<Vec3> vertices,
      FillMode fillMode,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode
   ) {
      if (vertices.size() != 4 || maxBlocks <= 0) {
         return Set.of();
      }
      if (fillMode == FillMode.OUTLINE) {
         return PlanarFaceRasterizer.interpolatedQuadOutline(
            vertices, maxBlocks, observer, tieBias, rasterizationMode
         );
      }
      Set<BlockPos> result = PlanarFaceRasterizer.generateInterpolatedQuad(
         vertices, maxBlocks, observer, tieBias, rasterizationMode
      );
      if (!result.isEmpty()) {
         return result;
      }
      Set<BlockPos> fallback = new ObservedBlockSet(observer);
      fallback.addAll(PlanarFaceGeometry.outline(vertices, maxBlocks, tieBias));
      return fallback;
   }
}
