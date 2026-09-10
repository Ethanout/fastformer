package io.github.fastformer.fastplace.geometry.generation;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraft.core.BlockPos;

/**
 * Read-only, deterministic view over an integer box.  The caller supplies the
 * exact accepted count and a predicate; rejected coordinates are never boxed.
 */
final class LazyBlockSet extends AbstractSet<BlockPos> {
   private final int minimumX;
   private final int minimumY;
   private final int minimumZ;
   private final int maximumX;
   private final int maximumY;
   private final int maximumZ;
   private final int size;
   private final CoordinatePredicate predicate;

   LazyBlockSet(
      int minimumX,
      int minimumY,
      int minimumZ,
      int maximumX,
      int maximumY,
      int maximumZ,
      int size,
      CoordinatePredicate predicate
   ) {
      if (predicate == null || size < 0) {
         throw new IllegalArgumentException("A lazy block set requires a predicate and non-negative size");
      }
      this.minimumX = minimumX;
      this.minimumY = minimumY;
      this.minimumZ = minimumZ;
      this.maximumX = maximumX;
      this.maximumY = maximumY;
      this.maximumZ = maximumZ;
      this.size = size;
      this.predicate = predicate;
   }

   @Override
   public int size() {
      return this.size;
   }

   @Override
   public Iterator<BlockPos> iterator() {
      return new Iterator<>() {
         private long x = LazyBlockSet.this.minimumX;
         private long y = LazyBlockSet.this.minimumY;
         private long z = LazyBlockSet.this.minimumZ;
         private BlockPos next;

         @Override
         public boolean hasNext() {
            if (this.next != null) {
               return true;
            }
            while (this.x <= LazyBlockSet.this.maximumX) {
               while (this.y <= LazyBlockSet.this.maximumY) {
                  while (this.z <= LazyBlockSet.this.maximumZ) {
                     int candidateX = (int)this.x;
                     int candidateY = (int)this.y;
                     int candidateZ = (int)this.z++;
                     if (LazyBlockSet.this.predicate.test(candidateX, candidateY, candidateZ)) {
                        this.next = new BlockPos(candidateX, candidateY, candidateZ);
                        return true;
                     }
                  }
                  this.z = LazyBlockSet.this.minimumZ;
                  this.y++;
               }
               this.y = LazyBlockSet.this.minimumY;
               this.x++;
            }
            return false;
         }

         @Override
         public BlockPos next() {
            if (!hasNext()) {
               throw new NoSuchElementException();
            }
            BlockPos result = this.next;
            this.next = null;
            return result;
         }
      };
   }

   @FunctionalInterface
   interface CoordinatePredicate {
      boolean test(int x, int y, int z);
   }
}
