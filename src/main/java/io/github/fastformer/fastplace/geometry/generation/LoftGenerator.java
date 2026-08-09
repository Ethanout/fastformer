package io.github.fastformer.fastplace.geometry.generation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class LoftGenerator {
   private LoftGenerator() {
   }

   public static Set<BlockPos> generate(List<BlockPos> points, int maxBlocks) {
      if (points.size() < 4) {
         return Set.copyOf(points);
      }
      int half = points.size() / 2;
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int index = 0; index < half && index + half < points.size() && result.size() < maxBlocks; index++) {
         addLine(result, points.get(index), points.get(index + half), maxBlocks);
         if (index > 0) {
            addLine(result, points.get(index - 1), points.get(index), maxBlocks);
            addLine(result, points.get(index + half - 1), points.get(index + half), maxBlocks);
         }
      }
      return Set.copyOf(result);
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
