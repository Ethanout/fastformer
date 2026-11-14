package io.github.fastformer.common.geometry.generation.face.entry;

import io.github.fastformer.common.geometry.generation.face.*;
import io.github.fastformer.common.geometry.generation.source.*;
import io.github.fastformer.core.math.SaturatedArithmetic;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.raster.face.policy.FacePolicyObserver;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicies;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicy;
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
      return generate(vertices, fillMode, maxBlocks, observer, FacePolicyObserver.policies(observer).polygon());
   }

   /** A task may pass one immutable policy snapshot to every generated polygon section. */
   public static Set<BlockPos> generate(
      List<Vec3> vertices, FillMode fillMode, int maxBlocks, BlockGenerationObserver observer, FaceRasterPolicy policy
   ) {
      if (vertices.size() < 3 || maxBlocks <= 0) {
         return Set.of();
      }
      var dispatcher = policy == FaceRasterPolicies.CURRENT_POLYGON
         ? FaceRasterAdapters.currentPolygon() : FaceRasterAdapters.currentPolygon(policy);
      Set<BlockPos> result = FaceRasterAdapters.generatedBlocks(
         dispatcher.rasterize(new FaceRequest(vertices, fillMode, LineTieBias.DEFAULT, maxBlocks, observer)), maxBlocks, observer
      );
      if (fillMode == FillMode.OUTLINE) {
         return result;
      }
      if (!result.isEmpty()) {
         return result;
      }
      Set<BlockPos> outline = FaceRasterAdapters.generatedBlocks(
         dispatcher.rasterize(new FaceRequest(vertices, FillMode.OUTLINE, LineTieBias.DEFAULT, maxBlocks, observer)),
         maxBlocks, observer
      );
      if (GenerationLimitExceeded.is(outline)) {
         return outline;
      }
      Set<BlockPos> fallback = GeneratedBlockSets.observed(observer);
      fallback.addAll(outline);
      return fallback;
   }

   private static Set<BlockPos> observedCopy(List<BlockPos> points, BlockGenerationObserver observer) {
      Set<BlockPos> result = GeneratedBlockSets.observed(observer);
      result.addAll(points);
      return result;
   }
}
