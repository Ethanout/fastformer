package io.github.fastformer.fastplace.geometry.generation;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class LoftGenerator {
   private LoftGenerator() {
   }

   public static Set<BlockPos> generate(List<BlockPos> points, int maxBlocks) {
      if (points.size() < 4) {
         return GenerationLimitExceeded.boundedResult(Set.copyOf(points), maxBlocks, BlockGenerationObserver.NONE);
      }
      int stagingLimit = GenerationLimitExceeded.probeLimit(maxBlocks);
      int half = points.size() / 2;
      Set<BlockPos> result = new ObservedBlockSet(BlockGenerationObserver.NONE);
      for (int index = 0; index < half && index + half < points.size() && result.size() < stagingLimit; index++) {
         addLine(result, points.get(index), points.get(index + half), stagingLimit);
         if (index > 0) {
            addLine(result, points.get(index - 1), points.get(index), stagingLimit);
            addLine(result, points.get(index + half - 1), points.get(index + half), stagingLimit);
         }
      }
      return GenerationLimitExceeded.boundedResult(result, maxBlocks, BlockGenerationObserver.NONE);
   }

   /** Typed boundary used by server placement; preview callers may keep using generate. */
   public static BlockGenerationResult generateResult(List<BlockPos> points, int maxBlocks) {
      return BlockGenerationResult.fromLegacy(generate(points, maxBlocks));
   }

   public static long estimateScanCells(List<BlockPos> points) {
      if (points.size() < 4) {
         return points.size();
      }
      int half = points.size() / 2;
      long scanCells = 0L;
      for (int index = 0; index < half && index + half < points.size(); index++) {
         scanCells = GenerationMath.saturatedAdd(
            scanCells,
            LineGenerator.estimateBlocks(points.get(index), points.get(index + half))
         );
         if (index > 0) {
            scanCells = GenerationMath.saturatedAdd(
               scanCells,
               LineGenerator.estimateBlocks(points.get(index - 1), points.get(index))
            );
            scanCells = GenerationMath.saturatedAdd(
               scanCells,
               LineGenerator.estimateBlocks(points.get(index + half - 1), points.get(index + half))
            );
         }
      }
      return scanCells;
   }

   private static void addLine(Set<BlockPos> result, BlockPos from, BlockPos to, int maxBlocks) {
      LineGenerator.add(result, Vec3.atCenterOf(from), Vec3.atCenterOf(to), maxBlocks);
   }
}
