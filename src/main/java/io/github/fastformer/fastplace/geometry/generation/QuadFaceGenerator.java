package io.github.fastformer.fastplace.geometry.generation;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.raster.face.policy.FacePolicyObserver;

import io.github.fastformer.common.geometry.LineTieBias;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicies;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicy;
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
      return generate(vertices, fillMode, maxBlocks, observer, tieBias, FacePolicyObserver.policies(observer).quad());
   }

   /** A task can freeze its policy before generation without mutating shared render or server state. */
   public static Set<BlockPos> generate(
      List<Vec3> vertices,
      FillMode fillMode,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterPolicy policy
   ) {
      if (vertices.size() != 4 || maxBlocks <= 0) {
         return Set.of();
      }
      var dispatcher = policy == FaceRasterPolicies.CURRENT_QUAD
         ? FaceRasterAdapters.currentQuad() : FaceRasterAdapters.currentQuad(policy);
      Set<BlockPos> result = FaceRasterAdapters.generatedBlocks(
         dispatcher.rasterize(new FaceRequest(vertices, fillMode, tieBias, maxBlocks, observer)), maxBlocks, observer
      );
      if (fillMode == FillMode.OUTLINE) {
         return result;
      }
      if (!result.isEmpty()) {
         return result;
      }
      Set<BlockPos> fallback = new ObservedBlockSet(observer);
      fallback.addAll(PlanarFaceGeometry.outline(vertices, maxBlocks, tieBias));
      return fallback;
   }
}
