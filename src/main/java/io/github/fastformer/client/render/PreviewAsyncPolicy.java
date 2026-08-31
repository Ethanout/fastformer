package io.github.fastformer.client.render;

import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import io.github.fastformer.fastplace.geometry.generation.WallGenerator;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Keeps ordinary previews immediate and reserves background work for genuinely large scans. */
public final class PreviewAsyncPolicy {
   public static final long SYNCHRONOUS_SCAN_LIMIT = 16_000L;
   public static final int SYNCHRONOUS_GRID_LIMIT = 16_000;

   private PreviewAsyncPolicy() {
   }

   public static boolean generateSynchronously(List<BlockPos> points, Workload workload) {
      return estimateScanCells(points, workload) <= SYNCHRONOUS_SCAN_LIMIT;
   }

   public static boolean meshSynchronously(int blockCount) {
      return blockCount <= SYNCHRONOUS_GRID_LIMIT;
   }

   public static long estimateScanCells(List<BlockPos> points, Workload workload) {
      if (points.isEmpty()) {
         return 0L;
      }
      if (points.size() == 1) {
         return 1L;
      }
      if (workload == Workload.LINE) {
         return LineGenerator.estimateBlocks(points.getFirst(), points.getLast());
      }
      if (workload == Workload.PATH) {
         return WallGenerator.estimateScanCells(points, false, BlockPos.ZERO);
      }

      long[] spans = coordinateSpans(points);
      Arrays.sort(spans);
      return workload == Workload.PLANE
         ? saturatedMultiply(spans[1], spans[2])
         : saturatedMultiply(saturatedMultiply(spans[0], spans[1]), spans[2]);
   }

   private static long[] coordinateSpans(List<BlockPos> points) {
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      int maxZ = Integer.MIN_VALUE;
      for (BlockPos point : points) {
         minX = Math.min(minX, point.getX());
         minY = Math.min(minY, point.getY());
         minZ = Math.min(minZ, point.getZ());
         maxX = Math.max(maxX, point.getX());
         maxY = Math.max(maxY, point.getY());
         maxZ = Math.max(maxZ, point.getZ());
      }
      return new long[]{
         (long)maxX - minX + 1L,
         (long)maxY - minY + 1L,
         (long)maxZ - minZ + 1L
      };
   }

   private static long saturatedMultiply(long left, long right) {
      return left <= 0L || right <= 0L
         ? 0L
         : left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
   }

   public enum Workload {
      LINE,
      PATH,
      PLANE,
      VOLUME
   }
}
