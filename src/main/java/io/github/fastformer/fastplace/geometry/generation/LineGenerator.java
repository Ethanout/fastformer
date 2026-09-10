package io.github.fastformer.fastplace.geometry.generation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class LineGenerator {
   private LineGenerator() {
   }

   public static Set<BlockPos> generate(BlockPos from, BlockPos to, int maxBlocks) {
      return generate(from, to, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generate(BlockPos from, BlockPos to, int maxBlocks, BlockGenerationObserver observer) {
      return generate(from, to, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   public static Set<BlockPos> generate(
      BlockPos from,
      BlockPos to,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      if (maxBlocks > 0 && estimateBlocks(from, to) > maxBlocks) {
         return GenerationLimitExceeded.witness(maxBlocks, observer);
      }
      if (maxBlocks <= 0) {
         return Set.of();
      }
      return new LazyLineBlockSet(from, to, tieBias, observer);
   }

   public static BlockPositionSource generateSource(
      BlockPos from, BlockPos to, int maxBlocks, BlockGenerationObserver observer, LineTieBias tieBias
   ) {
      if (maxBlocks > 0 && estimateBlocks(from, to) > maxBlocks) {
         return new SetPositionSource(GenerationLimitExceeded.witness(maxBlocks, observer));
      }
      if (maxBlocks <= 0) {
         return new SetPositionSource(Set.of());
      }
      return new SetPositionSource(new LazyLineBlockSet(from, to, tieBias, observer));
   }

   private record SetPositionSource(Set<BlockPos> blocks) implements BlockPositionSource {
      @Override
      public int size() {
         return this.blocks.size();
      }

      @Override
      public java.util.Iterator<BlockPos> iterator() {
         return this.blocks.iterator();
      }

      @Override
      public java.util.Iterator<BlockPos> drainingIterator() {
         return this.blocks instanceof DrainingBlockSet draining ? draining.drainingIterator() : iterator();
      }

      @Override
      public boolean supportsDraining() {
         return this.blocks instanceof DrainingBlockSet;
      }
   }

   public static List<BlockPos> offsets(Vec3 delta, int maxBlocks) {
      return offsets(delta, maxBlocks, LineTieBias.DEFAULT);
   }

   public static List<BlockPos> offsets(Vec3 delta, int maxBlocks, LineTieBias tieBias) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      add(result, Vec3.ZERO, delta, maxBlocks, tieBias);
      return List.copyOf(result);
   }

   public static long estimateBlocks(BlockPos from, BlockPos to) {
      return 1L + Math.max(
         Math.max(Math.abs((long)to.getX() - from.getX()), Math.abs((long)to.getY() - from.getY())),
         Math.abs((long)to.getZ() - from.getZ())
      );
   }

   public static void add(Set<BlockPos> output, Vec3 from, Vec3 to, int maxBlocks) {
      add(output, from, to, maxBlocks, LineTieBias.DEFAULT);
   }

   public static void add(Set<BlockPos> output, Vec3 from, Vec3 to, int maxBlocks, LineTieBias tieBias) {
      if (maxBlocks <= 0 || output.size() >= maxBlocks) {
         return;
      }
      BlockPos start = BlockPos.containing(from);
      BlockPos end = BlockPos.containing(to);
      add(output, start, end, maxBlocks, tieBias);
   }

   /**
    * Adds a three-axis Bresenham path.  The dominant axis advances on every
    * step; secondary-axis advances are distributed with integer error terms,
    * and the two secondary axes are paired whenever possible.  Consequently
    * every coordinate-plane projection is a digital straight path rather than
    * a sequence of two orthogonal turns.
    */
   public static void add(Set<BlockPos> output, BlockPos from, BlockPos to, int maxBlocks) {
      add(output, from, to, maxBlocks, LineTieBias.DEFAULT);
   }

   public static void add(Set<BlockPos> output, BlockPos from, BlockPos to, int maxBlocks, LineTieBias tieBias) {
      if (maxBlocks <= 0 || output.size() >= maxBlocks) {
         return;
      }
      LineTieBias effectiveBias = LineTieBias.orDefault(tieBias);
      BlockPos start = compare(from, to) <= 0 ? from : to;
      BlockPos end = start == from ? to : from;
      long[] delta = {
         (long)end.getX() - start.getX(),
         (long)end.getY() - start.getY(),
         (long)end.getZ() - start.getZ()
      };
      long[] distance = {Math.abs(delta[0]), Math.abs(delta[1]), Math.abs(delta[2])};
      int[] axes = axesByDescendingSlope(distance);
      int major = axes[0];
      int middle = axes[1];
      int minor = axes[2];
      long majorSteps = distance[major];
      if (majorSteps == 0L) {
         output.add(start.immutable());
         return;
      }

      int majorSign = sign(delta[major]);
      int middleSign = sign(delta[middle]);
      int minorSign = sign(delta[minor]);
      long middleError = 2L * distance[middle] - majorSteps;
      long minorError = 2L * distance[minor] - distance[middle];
      long x = start.getX();
      long y = start.getY();
      long z = start.getZ();
      for (long step = 0; step <= majorSteps && output.size() < maxBlocks; step++) {
         output.add(new BlockPos(safeInt(x), safeInt(y), safeInt(z)));
         if (step == majorSteps) {
            break;
         }

         x = addCoordinate(x, major == 0 ? majorSign : 0);
         y = addCoordinate(y, major == 1 ? majorSign : 0);
         z = addCoordinate(z, major == 2 ? majorSign : 0);

         if (effectiveBias.advances(middleError)) {
            x = addCoordinate(x, middle == 0 ? middleSign : 0);
            y = addCoordinate(y, middle == 1 ? middleSign : 0);
            z = addCoordinate(z, middle == 2 ? middleSign : 0);
            middleError -= 2L * majorSteps;

            // The least-sloped axis advances only inside a middle-axis event.
            // This permits A, AB and ABC steps, but never the AC step that
            // creates an internal right-angle pixel in the BC projection.
            if (effectiveBias.advances(minorError)) {
               x = addCoordinate(x, minor == 0 ? minorSign : 0);
               y = addCoordinate(y, minor == 1 ? minorSign : 0);
               z = addCoordinate(z, minor == 2 ? minorSign : 0);
               minorError -= 2L * distance[middle];
            }
            minorError += 2L * distance[minor];
         }
         middleError += 2L * distance[middle];
      }
   }

   public static List<BlockPos> path(BlockPos from, BlockPos to) {
      return path(from, to, LineTieBias.DEFAULT);
   }

   public static List<BlockPos> path(BlockPos from, BlockPos to, LineTieBias tieBias) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      long estimate = estimateBlocks(from, to);
      add(result, from, to, estimate >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)estimate, tieBias);
      return List.copyOf(result);
   }

   static int[] axesByDescendingSlope(BlockPos from, BlockPos to) {
      return axesByDescendingSlope(new long[]{
         Math.abs((long)to.getX() - from.getX()),
         Math.abs((long)to.getY() - from.getY()),
         Math.abs((long)to.getZ() - from.getZ())
      });
   }

   static int[] axesByDescendingSlope(long[] distance) {
      int[] result = {0, 1, 2};
      for (int index = 1; index < result.length; index++) {
         int axis = result[index];
         int previous = index - 1;
         while (previous >= 0 && distance[axis] > distance[result[previous]]) {
            result[previous + 1] = result[previous];
            previous--;
         }
         result[previous + 1] = axis;
      }
      return result;
   }

   private static int sign(long value) {
      return Long.compare(value, 0L);
   }

   private static long addCoordinate(long value, int delta) {
      return value + delta;
   }

   private static int safeInt(long value) {
      return value < Integer.MIN_VALUE ? Integer.MIN_VALUE : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)value;
   }

   private static int compare(BlockPos first, BlockPos second) {
      int x = Integer.compare(first.getX(), second.getX());
      if (x != 0) {
         return x;
      }
      int y = Integer.compare(first.getY(), second.getY());
      return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
   }

   static BlockPos orderedStart(BlockPos first, BlockPos second) {
      return compare(first, second) <= 0 ? first : second;
   }

}
