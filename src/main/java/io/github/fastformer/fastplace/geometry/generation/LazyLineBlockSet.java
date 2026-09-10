package io.github.fastformer.fastplace.geometry.generation;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraft.core.BlockPos;

/**
 * Lazy Bresenham line. The iterator keeps only the current voxel and error
 * terms, so a long line does not retain one object per generated position.
 */
final class LazyLineBlockSet extends AbstractSet<BlockPos> implements DrainingBlockSet {
   private final BlockPos start;
   private final int[] majorAxes;
   private final long[] distance;
   private final int[] signs;
   private final long majorSteps;
   private final LineTieBias tieBias;
   private final BlockGenerationObserver observer;

   LazyLineBlockSet(BlockPos from, BlockPos to, LineTieBias tieBias, BlockGenerationObserver observer) {
      this.start = LineGenerator.orderedStart(from, to);
      BlockPos end = this.start.equals(from) ? to : from;
      long[] delta = {
         (long)end.getX() - this.start.getX(),
         (long)end.getY() - this.start.getY(),
         (long)end.getZ() - this.start.getZ()
      };
      this.distance = new long[] {
         Math.abs(delta[0]), Math.abs(delta[1]), Math.abs(delta[2])
      };
      this.majorAxes = LineGenerator.axesByDescendingSlope(this.distance);
      this.signs = new int[] {
         Long.compare(delta[0], 0L), Long.compare(delta[1], 0L), Long.compare(delta[2], 0L)
      };
      this.majorSteps = this.distance[this.majorAxes[0]];
      this.tieBias = LineTieBias.orDefault(tieBias);
      this.observer = observer == null ? BlockGenerationObserver.NONE : observer;
      this.observer.checkCancelled();
   }

   @Override
   public int size() {
      return this.majorSteps >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)this.majorSteps + 1;
   }

   @Override
   public Iterator<BlockPos> iterator() {
      return new Iterator<>() {
         private final int major = LazyLineBlockSet.this.majorAxes[0];
         private final int middle = LazyLineBlockSet.this.majorAxes[1];
         private final int minor = LazyLineBlockSet.this.majorAxes[2];
         private long x = LazyLineBlockSet.this.start.getX();
         private long y = LazyLineBlockSet.this.start.getY();
         private long z = LazyLineBlockSet.this.start.getZ();
         private long middleError = 2L * LazyLineBlockSet.this.distance[this.middle]
            - LazyLineBlockSet.this.majorSteps;
         private long minorError = 2L * LazyLineBlockSet.this.distance[this.minor]
            - LazyLineBlockSet.this.distance[this.middle];
         private long step;

         @Override
         public boolean hasNext() {
            return this.step <= LazyLineBlockSet.this.majorSteps;
         }

         @Override
         public BlockPos next() {
            if (!hasNext()) {
               throw new NoSuchElementException();
            }
            LazyLineBlockSet.this.observer.checkCancelled();
            LazyLineBlockSet.this.observer.onScanned(1L);
            BlockPos result = new BlockPos(
               LazyLineBlockSet.safeInt(this.x),
               LazyLineBlockSet.safeInt(this.y),
               LazyLineBlockSet.safeInt(this.z)
            );
            LazyLineBlockSet.this.observer.onGenerated(result);
            advance();
            return result;
         }

         private void advance() {
            if (this.step++ == LazyLineBlockSet.this.majorSteps) {
               return;
            }
            this.x = advanceCoordinate(this.x, this.major == 0 ? LazyLineBlockSet.this.signs[this.major] : 0);
            this.y = advanceCoordinate(this.y, this.major == 1 ? LazyLineBlockSet.this.signs[this.major] : 0);
            this.z = advanceCoordinate(this.z, this.major == 2 ? LazyLineBlockSet.this.signs[this.major] : 0);
            if (LazyLineBlockSet.this.tieBias.advances(this.middleError)) {
               this.x = advanceCoordinate(this.x, this.middle == 0 ? LazyLineBlockSet.this.signs[this.middle] : 0);
               this.y = advanceCoordinate(this.y, this.middle == 1 ? LazyLineBlockSet.this.signs[this.middle] : 0);
               this.z = advanceCoordinate(this.z, this.middle == 2 ? LazyLineBlockSet.this.signs[this.middle] : 0);
               this.middleError -= 2L * LazyLineBlockSet.this.majorSteps;
               if (LazyLineBlockSet.this.tieBias.advances(this.minorError)) {
                  this.x = advanceCoordinate(this.x, this.minor == 0 ? LazyLineBlockSet.this.signs[this.minor] : 0);
                  this.y = advanceCoordinate(this.y, this.minor == 1 ? LazyLineBlockSet.this.signs[this.minor] : 0);
                  this.z = advanceCoordinate(this.z, this.minor == 2 ? LazyLineBlockSet.this.signs[this.minor] : 0);
                  this.minorError -= 2L * LazyLineBlockSet.this.distance[this.middle];
               }
               this.minorError += 2L * LazyLineBlockSet.this.distance[this.minor];
            }
            this.middleError += 2L * LazyLineBlockSet.this.distance[this.middle];
         }
      };
   }

   @Override
   public Iterator<BlockPos> drainingIterator() {
      return iterator();
   }

   private static long advanceCoordinate(long value, int delta) {
      return value + delta;
   }

   private static int safeInt(long value) {
      return value < Integer.MIN_VALUE ? Integer.MIN_VALUE
         : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)value;
   }
}
