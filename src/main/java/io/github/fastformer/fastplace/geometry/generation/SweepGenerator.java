package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

public final class SweepGenerator {
   private SweepGenerator() {
   }

   public static Set<BlockPos> generate(List<BlockPos> points, FillMode fillMode, int maxBlocks) {
      if (points.size() < 3) {
         return GenerationLimitExceeded.boundedResult(Set.copyOf(points), maxBlocks, BlockGenerationObserver.NONE);
      }
      int stagingLimit = GenerationLimitExceeded.probeLimit(maxBlocks);
      double radius = Math.max(1.0, Math.sqrt(points.getFirst().distSqr(points.get(1))));
      Set<BlockPos> result = new ObservedBlockSet(BlockGenerationObserver.NONE);
      for (int index = 2; index < points.size() && result.size() < stagingLimit; index++) {
         addBall(result, points.get(index), radius, fillMode, stagingLimit);
         if (index > 2) {
            addSweptSegment(result, points.get(index - 1), points.get(index), radius, fillMode, stagingLimit);
         }
      }
      return GenerationLimitExceeded.boundedResult(result, maxBlocks, BlockGenerationObserver.NONE);
   }

   /** Typed boundary used by server placement; preview callers may keep using generate. */
   public static BlockGenerationResult generateResult(List<BlockPos> points, FillMode fillMode, int maxBlocks) {
      return BlockGenerationResult.fromLegacy(generate(points, fillMode, maxBlocks));
   }

   public static long estimateScanCells(List<BlockPos> points) {
      if (points.size() < 3) {
         return points.size();
      }
      int extent = (int)Math.ceil(Math.max(1.0, Math.sqrt(points.getFirst().distSqr(points.get(1)))));
      long width = 2L * extent + 1L;
      long ballScanCells = GenerationMath.saturatedMultiply(GenerationMath.saturatedMultiply(width, width), width);
      long ballCount = points.size() - 2L;
      for (int index = 3; index < points.size(); index++) {
         ballCount = GenerationMath.saturatedAdd(
            ballCount,
            LineGenerator.estimateBlocks(points.get(index - 1), points.get(index))
         );
      }
      return GenerationMath.saturatedMultiply(ballScanCells, ballCount);
   }

   private static void addSweptSegment(
      Set<BlockPos> result, BlockPos from, BlockPos to, double radius, FillMode fillMode, int maxBlocks
   ) {
      int steps = Math.max(
         1,
         Math.max(Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getY() - from.getY())), Math.abs(to.getZ() - from.getZ()))
      );
      for (int step = 0; step <= steps && result.size() < maxBlocks; step++) {
         double ratio = (double)step / (double)steps;
         BlockPos center = new BlockPos(
            (int)Math.round(from.getX() + (to.getX() - from.getX()) * ratio),
            (int)Math.round(from.getY() + (to.getY() - from.getY()) * ratio),
            (int)Math.round(from.getZ() + (to.getZ() - from.getZ()) * ratio)
         );
         addBall(result, center, radius, fillMode, maxBlocks);
      }
   }

   private static void addBall(Set<BlockPos> result, BlockPos center, double radius, FillMode fillMode, int maxBlocks) {
      int extent = (int)Math.ceil(radius);
      double innerRadius = Math.max(0.0, radius - 1.0);
      for (int x = -extent; x <= extent && result.size() < maxBlocks; x++) {
         for (int y = -extent; y <= extent && result.size() < maxBlocks; y++) {
            for (int z = -extent; z <= extent && result.size() < maxBlocks; z++) {
               double distanceSqr = x * x + y * y + z * z;
               if (distanceSqr <= radius * radius && (fillMode == FillMode.SOLID || distanceSqr >= innerRadius * innerRadius)) {
                  result.add(center.offset(x, y, z));
               }
            }
         }
      }
   }
}
