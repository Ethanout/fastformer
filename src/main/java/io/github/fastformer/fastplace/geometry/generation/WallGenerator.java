package io.github.fastformer.fastplace.geometry.generation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

public final class WallGenerator {
   private WallGenerator() {
   }

   public static Set<BlockPos> generate(List<BlockPos> points, boolean closed, BlockPos extrusion, int maxBlocks) {
      return generate(points, closed, extrusion, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generate(
      List<BlockPos> points, boolean closed, BlockPos extrusion, int maxBlocks, BlockGenerationObserver observer
   ) {
      if (points.size() < 2) {
         Set<BlockPos> result = new ObservedBlockSet(observer);
         result.addAll(points);
         return result;
      }
      Set<BlockPos> result = new ObservedBlockSet(observer);
      int layers = Math.max(Math.abs(extrusion.getX()), Math.max(Math.abs(extrusion.getY()), Math.abs(extrusion.getZ())));
      for (int layer = 0; layer <= layers && result.size() < maxBlocks; layer++) {
         double ratio = layers == 0 ? 0.0 : (double)layer / (double)layers;
         BlockPos offset = new BlockPos(
            (int)Math.round(extrusion.getX() * ratio),
            (int)Math.round(extrusion.getY() * ratio),
            (int)Math.round(extrusion.getZ() * ratio)
         );
         int edgeCount = closed ? points.size() : points.size() - 1;
         for (int edge = 0; edge < edgeCount && result.size() < maxBlocks; edge++) {
            BlockPos from = points.get(edge).offset(offset);
            BlockPos to = points.get((edge + 1) % points.size()).offset(offset);
            LineGenerator.add(result, from, to, maxBlocks);
         }
      }
      return Set.copyOf(result);
   }

   public static long estimateScanCells(List<BlockPos> points, boolean closed, BlockPos extrusion) {
      if (points.size() < 2) {
         return points.size();
      }
      long edgeCells = 0L;
      int edgeCount = closed ? points.size() : points.size() - 1;
      for (int edge = 0; edge < edgeCount; edge++) {
         edgeCells = GenerationMath.saturatedAdd(
            edgeCells,
            LineGenerator.estimateBlocks(points.get(edge), points.get((edge + 1) % points.size()))
         );
      }
      long layers = 1L + Math.max(
         Math.abs((long)extrusion.getX()),
         Math.max(Math.abs((long)extrusion.getY()), Math.abs((long)extrusion.getZ()))
      );
      return GenerationMath.saturatedMultiply(edgeCells, layers);
   }
}
