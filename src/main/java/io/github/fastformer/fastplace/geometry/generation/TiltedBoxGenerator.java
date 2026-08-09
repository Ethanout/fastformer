package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.FaceRasterizationMode;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class TiltedBoxGenerator {
   private static final double EPSILON = 1.0E-7;
   private static final long DEGENERATE_EXACT_AABB_VOLUME_LIMIT = 1_000_000L;
   private static final int[][] SIX_NEIGHBORS = {
      {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
   };

   private TiltedBoxGenerator() {
   }

   public static Set<BlockPos> generate(List<Vec3> base, Vec3 extrusion, FillMode fillMode, int maxBlocks) {
      return generate(base, extrusion, fillMode, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generate(
      List<Vec3> base, Vec3 extrusion, FillMode fillMode, int maxBlocks, BlockGenerationObserver observer
   ) {
      return generate(base, extrusion, fillMode, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   public static Set<BlockPos> generate(
      List<Vec3> base,
      Vec3 extrusion,
      FillMode fillMode,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return generate(
         base, extrusion, fillMode, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP
      );
   }

   public static Set<BlockPos> generate(
      List<Vec3> base,
      Vec3 extrusion,
      FillMode fillMode,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode
   ) {
      if (base.size() != 4 || maxBlocks <= 0) {
         return Set.of();
      }

      if (fillMode == FillMode.OUTLINE) {
         return outline(base, extrusion, maxBlocks, observer, tieBias, rasterizationMode);
      }
      Vec3 normal = PlanarFaceGeometry.normal(base);
      if (normal.lengthSqr() < EPSILON
         || extrusion.lengthSqr() < EPSILON
         || Math.abs(normal.dot(extrusion)) <= EPSILON * Math.sqrt(normal.lengthSqr() * extrusion.lengthSqr())) {
         BoundedAabbVolume box = boundingVolume(base, extrusion);
         if (box.solidCount() > DEGENERATE_EXACT_AABB_VOLUME_LIMIT) {
            BlockGenerationObserver staging = stagingObserver(observer);
            Set<BlockPos> staged = box.materialize(fillMode == FillMode.HOLLOW, maxBlocks, staging);
            if (GenerationLimitExceeded.is(staged)) {
               return GenerationLimitExceeded.witness(maxBlocks, observer);
            }
            return publish(staged, maxBlocks, observer);
         }
         BlockGenerationObserver solidObserver = fillMode == FillMode.SOLID
            ? observer
            : stagingObserver(observer);
         Set<BlockPos> solid = degenerateSolid(
            base,
            extrusion,
            fillMode == FillMode.SOLID ? maxBlocks : Integer.MAX_VALUE,
            solidObserver,
            tieBias,
            rasterizationMode
         );
         if (GenerationLimitExceeded.is(solid)) {
            return GenerationLimitExceeded.witness(maxBlocks, observer);
         }
         return fillMode == FillMode.SOLID ? solid : hollow(solid, maxBlocks, observer);
      }
      BlockGenerationObserver solidObserver = fillMode == FillMode.SOLID
         ? observer
         : stagingObserver(observer);
      BresenhamColumnVolume.Result generated = fillMode == FillMode.SOLID
         ? BresenhamColumnVolume.generate(base, extrusion, maxBlocks, solidObserver, tieBias, rasterizationMode)
         : BresenhamColumnVolume.generateBoundary(base, extrusion, maxBlocks, solidObserver, tieBias, rasterizationMode);
      if (!generated.complete()) {
         return GenerationFailed.is(generated.blocks())
            ? generated.blocks()
            : GenerationLimitExceeded.witness(maxBlocks, observer);
      }
      if (fillMode == FillMode.SOLID) {
         return generated.blocks();
      }
      return publish(generated.blocks(), maxBlocks, observer);
   }

   private static BoundedAabbVolume boundingVolume(List<Vec3> base, Vec3 extrusion) {
      java.util.ArrayList<Vec3> vertices = new java.util.ArrayList<>(8);
      vertices.addAll(base);
      base.stream().map(vertex -> vertex.add(extrusion)).forEach(vertices::add);
      return BoundedAabbVolume.containing(vertices);
   }

   private static Set<BlockPos> hollow(Set<BlockPos> solid, int maxBlocks, BlockGenerationObserver observer) {
      BlockGenerationObserver staging = stagingObserver(observer);
      LinkedHashSet<BlockPos> staged = new LinkedHashSet<>();
      for (BlockPos position : solid) {
         staging.checkCancelled();
         staging.onScanned(1L);
         if (isBoundary(position, solid)) {
            if (staged.size() >= maxBlocks) {
               return sentinel(maxBlocks);
            }
            staged.add(position.immutable());
         }
      }
      return publish(staged, maxBlocks, observer);
   }

   private static boolean isBoundary(BlockPos position, Set<BlockPos> solid) {
      for (int[] neighbor : SIX_NEIGHBORS) {
         if (!solid.contains(position.offset(neighbor[0], neighbor[1], neighbor[2]))) {
            return true;
         }
      }
      return false;
   }

   private static Set<BlockPos> outline(
      List<Vec3> base, Vec3 extrusion, int maxBlocks, BlockGenerationObserver observer
   ) {
      return outline(base, extrusion, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   private static Set<BlockPos> outline(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return outline(
         base, extrusion, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP
      );
   }

   private static Set<BlockPos> outline(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode
   ) {
      int stagingLimit = onePast(maxBlocks);
      BlockGenerationObserver staging = stagingObserver(observer);
      Set<BlockPos> staged = new ObservedBlockSet(staging);
      List<Vec3> top = base.stream().map(vertex -> vertex.add(extrusion)).toList();
      for (List<Vec3> face : boxFaces(base, top)) {
         Set<BlockPos> faceOutline = PlanarFaceRasterizer.interpolatedQuadNaturalOutline(
            face,
            stagingLimit,
            staging,
            tieBias,
            rasterizationMode
         );
         if (GenerationLimitExceeded.is(faceOutline)) {
            return GenerationLimitExceeded.witness(maxBlocks, observer);
         }
         if (GenerationFailed.is(faceOutline)) {
            return faceOutline;
         }
         for (BlockPos block : faceOutline) {
            if (!staged.contains(block) && staged.size() >= maxBlocks) {
               return sentinel(maxBlocks);
            }
            staged.add(block);
         }
         if (staged.size() > maxBlocks) {
            return sentinel(maxBlocks);
         }
      }
      return publish(staged, maxBlocks, observer);
   }

   private static List<List<Vec3>> boxFaces(List<Vec3> base, List<Vec3> top) {
      java.util.ArrayList<List<Vec3>> result = new java.util.ArrayList<>(6);
      result.add(List.copyOf(base));
      result.add(List.copyOf(top));
      for (int index = 0; index < 4; index++) {
         int next = (index + 1) % 4;
         result.add(List.of(base.get(index), base.get(next), top.get(next), top.get(index)));
      }
      return List.copyOf(result);
   }

   private static Set<BlockPos> degenerateSolid(
      List<Vec3> base, Vec3 extrusion, int maxBlocks, BlockGenerationObserver observer
   ) {
      return degenerateSolid(base, extrusion, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   private static Set<BlockPos> degenerateSolid(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return degenerateSolid(
         base, extrusion, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP
      );
   }

   private static Set<BlockPos> degenerateSolid(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode
   ) {
      int stagingLimit = onePast(maxBlocks);
      BlockGenerationObserver staging = stagingObserver(observer);
      List<BlockPos> offsets = LineGenerator.offsets(extrusion, stagingLimit, tieBias);
      if (offsets.isEmpty()) {
         return Set.of();
      }
      Set<BlockPos> face = QuadFaceGenerator.generate(
         base,
         FillMode.SOLID,
         stagingLimit,
         staging,
         tieBias,
         rasterizationMode
      );
      if (GenerationLimitExceeded.is(face)) {
         return GenerationLimitExceeded.witness(maxBlocks, observer);
      }
      if (face.size() > maxBlocks) {
         return sentinel(maxBlocks);
      }
      Set<BlockPos> staged = new ObservedBlockSet(staging);
      for (BlockPos offset : offsets) {
         for (BlockPos position : face) {
            BlockPos translated = position.offset(offset);
            if (!staged.contains(translated) && staged.size() >= maxBlocks) {
               return sentinel(maxBlocks);
            }
            staged.add(translated);
         }
      }
      return publish(staged, maxBlocks, observer);
   }

   private static Set<BlockPos> publish(
      Set<BlockPos> staged,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (staged.size() > maxBlocks) {
         return Set.of();
      }
      Set<BlockPos> result = new ObservedBlockSet(observer);
      result.addAll(staged);
      return Collections.unmodifiableSet(new LinkedHashSet<>(result));
   }

   private static Set<BlockPos> sentinel(int maxBlocks) {
      return GenerationLimitExceeded.witness(maxBlocks);
   }

   private static int onePast(int maxBlocks) {
      return maxBlocks == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxBlocks + 1;
   }

   private static BlockGenerationObserver stagingObserver(BlockGenerationObserver observer) {
      BlockGenerationObserver delegate = observer == null ? BlockGenerationObserver.NONE : observer;
      return new BlockGenerationObserver() {
         @Override
         public void onScanned(long amount) {
            delegate.onScanned(amount);
         }

         @Override
         public void checkCancelled() {
            delegate.checkCancelled();
         }
      };
   }

}
