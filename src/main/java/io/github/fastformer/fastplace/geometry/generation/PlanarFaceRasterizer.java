package io.github.fastformer.fastplace.geometry.generation;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.raster.line.LinePath;

import io.github.fastformer.common.geometry.LineTieBias;

import static io.github.fastformer.common.math.BlockQuads.isParallelogramAfterFlooring;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class PlanarFaceRasterizer {
   private static final double EPSILON = 1.0E-7;

   private PlanarFaceRasterizer() {
   }

   static Set<BlockPos> generate(List<Vec3> vertices, int maxBlocks) {
      return generate(vertices, maxBlocks, BlockGenerationObserver.NONE);
   }

   static Set<BlockPos> generate(List<Vec3> vertices, int maxBlocks, BlockGenerationObserver observer) {
      return generate(vertices, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   static Set<BlockPos> generate(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      if (vertices.size() < 3 || maxBlocks <= 0) {
         return Set.of();
      }
      Vec3 normal = PlanarFaceGeometry.normal(vertices);
      if (normal.lengthSqr() < EPSILON) {
         return Set.of();
      }
      return generateBresenham(vertices, maxBlocks, observer, tieBias);
   }

   static Set<BlockPos> forceCandidateFailureForTesting(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      if (vertices.size() < 3 || maxBlocks <= 0) {
         return Set.of();
      }
      Vec3 normal = PlanarFaceGeometry.normal(vertices);
      if (normal.lengthSqr() < EPSILON) {
         return Set.of();
      }
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices,
         observer == null ? BlockGenerationObserver.NONE : observer);
      return resolveSweepAttempt(
         vertices,
         frame,
         maxBlocks,
         observer,
         tieBias,
         BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE)
      );
   }

   static Set<BlockPos> outline(List<Vec3> vertices, int maxBlocks, BlockGenerationObserver observer) {
      return outline(vertices, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   static Set<BlockPos> outline(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      if (vertices.size() < 3 || maxBlocks <= 0) {
         return Set.of();
      }
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices, effectiveObserver);
      if (vertices.size() == 4 && frame != null && isParallelogramAfterFlooring(vertices)) {
         LinkedHashSet<BlockPos> exact = new LinkedHashSet<>();
         List<BlockPos> corners = vertices.stream().map(BlockPos::containing).toList();
         for (int index = 0; index < corners.size(); index++) {
            BlockPos first = corners.get(index);
            BlockPos second = corners.get((index + 1) % corners.size());
            if (LineGenerator.estimateBlocks(first, second) > maxBlocks) {
               return limitSentinel(maxBlocks, effectiveObserver);
            }
            effectiveObserver.checkCancelled();
            exact.addAll(LinePath.path(first, second, tieBias, maxBlocks, effectiveObserver));
            if (exact.size() > maxBlocks) {
               return limitSentinel(maxBlocks, effectiveObserver);
            }
         }
         return observedCopy(exact, maxBlocks, effectiveObserver);
      }
      ProjectedBresenhamFace.Raster projected = ProjectedBresenhamFace.outline(vertices, maxBlocks, effectiveObserver);
      return projected.limitExceeded() ? limitSentinel(maxBlocks, effectiveObserver)
         : observedCopy(projected.outline(), maxBlocks, effectiveObserver);
   }

   private static Set<BlockPos> generateBresenham(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices, effectiveObserver);
      BresenhamFaceSweep.Attempt swept = lineSweep(frame, vertices.size(), tieBias, maxBlocks, effectiveObserver);
      return resolveSweepAttempt(vertices, frame, maxBlocks, effectiveObserver, tieBias, swept);
   }

   private static Set<BlockPos> resolveSweepAttempt(
      List<Vec3> vertices,
      ProjectedBresenhamFace.Frame frame,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      BresenhamFaceSweep.Attempt swept
   ) {
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      if (swept.succeeded()) {
         return observedCopy(swept.result().fill(), maxBlocks, effectiveObserver);
      }
      if (swept.status() == BresenhamFaceSweep.Status.NOT_APPLICABLE) {
         ProjectedBresenhamFace.Raster projected = ProjectedBresenhamFace.raster(vertices, maxBlocks, effectiveObserver);
         return projected.limitExceeded() ? limitSentinel(maxBlocks, effectiveObserver)
            : observedCopy(projected.fill(), maxBlocks, effectiveObserver);
      }
      if (swept.status() == BresenhamFaceSweep.Status.NO_VALID_CANDIDATE) {
         return emergencyFallback(vertices, maxBlocks, effectiveObserver, tieBias);
      }
      return limitSentinel(maxBlocks, effectiveObserver);
   }

   static Set<BlockPos> emergencyFallback(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices, effectiveObserver);
      BresenhamFaceSweep.Attempt emergency = BresenhamFaceSweep.emergencyFallback(
         frame,
         tieBias,
         maxBlocks,
         effectiveObserver
      );
      if (emergency.succeeded()) {
         return observedCopy(emergency.result().fill(), maxBlocks, effectiveObserver);
      }
      if (emergency.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED) {
         return limitSentinel(maxBlocks, effectiveObserver);
      }
      return lastResortOwnedRaster(vertices, maxBlocks, effectiveObserver, tieBias);
   }

   /** Total safety net for an internal analytic-fallback invariant failure. */
   private static Set<BlockPos> lastResortOwnedRaster(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      var projected = ProjectedBresenhamFace.raster(vertices, maxBlocks, observer);
      if (projected.limitExceeded()) return limitSentinel(maxBlocks, observer);
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(projected.fill());
      List<BlockPos> corners = vertices.stream().map(BlockPos::containing).toList();
      for (int index = 0; index < corners.size(); index++) {
         BlockPos first = corners.get(index);
         BlockPos second = corners.get((index + 1) % corners.size());
         if (LinePath.estimateBlocks(first, second) > maxBlocks) return limitSentinel(maxBlocks, observer);
         result.addAll(LinePath.path(first, second, tieBias, maxBlocks, observer));
         if (result.size() > maxBlocks) {
            return limitSentinel(maxBlocks, observer);
         }
      }
      return observedCopy(result, maxBlocks, observer);
   }

   private static BresenhamFaceSweep.Attempt lineSweep(
      ProjectedBresenhamFace.Frame frame,
      int vertexCount,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (vertexCount != 4) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      return BresenhamFaceSweep.attempt(frame, tieBias, maxBlocks, observer);
   }

   static Set<BlockPos> generateInterpolatedQuad(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices, effectiveObserver);
      BresenhamFaceSweep.Attempt attempt = QuadFaceRasterizer.attempt(
         frame,
         tieBias,
         maxBlocks,
         effectiveObserver
      );
      if (attempt.succeeded()) {
         return observedCopy(attempt.result().fill(), maxBlocks, effectiveObserver);
      }
      if (attempt.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED) {
         return limitSentinel(maxBlocks, effectiveObserver);
      }
      // Preserve authored edges if the constrained raster has no valid candidate.
      // This deterministic fallback is bounded and still reports cancellation and limits.
      return lastResortOwnedRaster(vertices, maxBlocks, effectiveObserver, tieBias);
   }

   static Set<BlockPos> interpolatedQuadOutline(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices, effectiveObserver);
      Set<BlockPos> translated = translatedQuadOutline(vertices, maxBlocks, effectiveObserver, tieBias);
      if (translated != null) {
         return translated;
      }
      return interpolatedQuadNaturalOutline(
         vertices, maxBlocks, effectiveObserver, tieBias
      );
   }

   static Set<BlockPos> interpolatedQuadNaturalOutline(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices, effectiveObserver);
      BresenhamFaceSweep.Attempt attempt = QuadFaceRasterizer.attempt(
         frame,
         tieBias,
         maxBlocks,
         effectiveObserver
      );
      if (attempt.succeeded()) {
         return observedCopy(attempt.result().outline(), maxBlocks, effectiveObserver);
      }
      if (attempt.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED) {
         return limitSentinel(maxBlocks, effectiveObserver);
      }
      return outline(vertices, maxBlocks, effectiveObserver, tieBias);
   }

   /**
    * A quick-shape parallelogram owns two authored edges. Its opposite edges
    * must be exact integer translations, otherwise rasterizing all four edges
    * independently produces visibly different stair steps.
    */
   private static Set<BlockPos> translatedQuadOutline(
      List<Vec3> vertices,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      if (vertices.size() != 4 || maxBlocks <= 0 || !isParallelogramAfterFlooring(vertices)) {
         return null;
      }
      List<BlockPos> corners = vertices.stream().map(BlockPos::containing).toList();
      long firstLength = LineGenerator.estimateBlocks(corners.get(0), corners.get(1));
      long secondLength = LineGenerator.estimateBlocks(corners.get(0), corners.get(3));
      if (firstLength > maxBlocks || secondLength > maxBlocks) {
         return limitSentinel(maxBlocks, observer);
      }

      List<BlockPos> first = LinePath.path(corners.get(0), corners.get(1), tieBias, maxBlocks, observer);
      List<BlockPos> second = LinePath.path(corners.get(0), corners.get(3), tieBias, maxBlocks, observer);
      BlockPos firstOffset = corners.get(3).subtract(corners.get(0));
      BlockPos secondOffset = corners.get(1).subtract(corners.get(0));
      LinkedHashSet<BlockPos> exact = new LinkedHashSet<>();
      Set<BlockPos> result = new ObservedBlockSet(observer);
      exact.addAll(first);
      exact.addAll(second);
      first.forEach(block -> exact.add(block.offset(firstOffset)));
      second.forEach(block -> exact.add(block.offset(secondOffset)));
      if (exact.size() > maxBlocks) {
         return limitSentinel(maxBlocks, observer);
      }
      result.addAll(exact);
      return result;
   }

   private static Set<BlockPos> observedCopy(
      Set<BlockPos> source,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (source.size() > maxBlocks) {
         return limitSentinel(maxBlocks, observer);
      }
      Set<BlockPos> output = new ObservedBlockSet(observer);
      for (BlockPos position : source) {
         output.add(position);
      }
      return output;
   }

   /**
    * The public Set API has no failure channel. Callers pass one-past their
    * placement limit and reject this typed exact-size witness before world writes.
    * The witness must not depend on the face frame: restoring artificial local
    * coordinates can saturate at an int boundary and collapse distinct entries.
    */
   private static Set<BlockPos> limitSentinel(int maxBlocks, BlockGenerationObserver observer) {
      return GenerationLimitExceeded.witness(maxBlocks, observer);
   }

}
