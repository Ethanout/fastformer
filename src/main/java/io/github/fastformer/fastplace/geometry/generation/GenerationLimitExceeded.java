package io.github.fastformer.fastplace.geometry.generation;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** A typed, exact-size witness used when a Set-returning generator exceeds its budget. */
public final class GenerationLimitExceeded {
   private static final long X_COUNT = 1L << 26;
   private static final int X_OFFSET = 1 << 25;

   private GenerationLimitExceeded() {
   }

   public static boolean is(Set<?> blocks) {
      return blocks instanceof Witness;
   }

   static Set<BlockPos> witness(int size) {
      return witness(size, BlockGenerationObserver.NONE);
   }

   static Set<BlockPos> witness(int size, BlockGenerationObserver observer) {
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      effectiveObserver.checkCancelled();
      return new Witness(Math.max(0, size), effectiveObserver);
   }

   static int probeLimit(int maxBlocks) {
      return maxBlocks == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, maxBlocks + 1);
   }

   static Set<BlockPos> boundedResult(
      Set<BlockPos> blocks,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return blocks.size() > maxBlocks
         ? witness(maxBlocks, observer)
         : GeneratedBlockSets.readOnly(blocks);
   }

   /**
    * The iterator is lazy so an Integer.MAX_VALUE staging budget is still safe.
    * Its coordinates form a one-to-one encoding of every non-negative int index
    * and therefore cannot collapse through world-coordinate saturation.
    */
   private static final class Witness extends AbstractSet<BlockPos> {
      private final int size;
      private final BlockGenerationObserver observer;

      private Witness(int size, BlockGenerationObserver observer) {
         this.size = size;
         this.observer = observer;
      }

      @Override
      public Iterator<BlockPos> iterator() {
         return new Iterator<>() {
            private long index;

            @Override
            public boolean hasNext() {
               return this.index < Witness.this.size;
            }

            @Override
            public BlockPos next() {
               if (!this.hasNext()) {
                  throw new NoSuchElementException();
               }
               Witness.this.observer.checkCancelled();
               return position(this.index++);
            }
         };
      }

      @Override
      public int size() {
         return this.size;
      }

      @Override
      public boolean contains(Object candidate) {
         if (!(candidate instanceof BlockPos position) || position.getY() != 0 || position.getZ() < 0) {
            return false;
         }
         long localX = (long)position.getX() + X_OFFSET;
         if (localX < 0L || localX >= X_COUNT) {
            return false;
         }
         long index = (long)position.getZ() * X_COUNT + localX;
         return index >= 0L && index < this.size;
      }

      private static BlockPos position(long index) {
         int x = (int)(index % X_COUNT) - X_OFFSET;
         int z = (int)(index / X_COUNT);
         return new BlockPos(x, 0, z);
      }
   }
}
