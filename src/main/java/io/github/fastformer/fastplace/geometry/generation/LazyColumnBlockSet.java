package io.github.fastformer.fastplace.geometry.generation;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraft.core.BlockPos;

/**
 * Read-only block view that scans x/y columns and only visits each column's
 * accepted z span. The span provider owns shape-specific geometry.
 */
final class LazyColumnBlockSet extends AbstractSet<BlockPos> {
   private final int minimumX;
   private final int minimumY;
   private final int maximumX;
   private final int maximumY;
   private final int size;
   private final ColumnSpanProvider spanProvider;
   private final LazyBlockSet.CoordinatePredicate predicate;

   LazyColumnBlockSet(
      int minimumX,
      int minimumY,
      int maximumX,
      int maximumY,
      int size,
      ColumnSpanProvider spanProvider,
      LazyBlockSet.CoordinatePredicate predicate
   ) {
      if (spanProvider == null || predicate == null || size < 0) {
         throw new IllegalArgumentException("A lazy column block set requires providers and non-negative size");
      }
      this.minimumX = minimumX;
      this.minimumY = minimumY;
      this.maximumX = maximumX;
      this.maximumY = maximumY;
      this.size = size;
      this.spanProvider = spanProvider;
      this.predicate = predicate;
   }

   @Override
   public int size() {
      return this.size;
   }

   @Override
   public Iterator<BlockPos> iterator() {
      return new Iterator<>() {
         private long x = LazyColumnBlockSet.this.minimumX;
         private long y = LazyColumnBlockSet.this.minimumY;
         private long z;
         private long maximumZ;
         private int columnY;
         private boolean columnActive;
         private BlockPos next;

         @Override
         public boolean hasNext() {
            if (this.next != null) {
               return true;
            }
            while (this.x <= LazyColumnBlockSet.this.maximumX) {
               if (findInActiveColumn()) {
                  return true;
               }
               while (this.y <= LazyColumnBlockSet.this.maximumY) {
                  this.columnY = (int)this.y++;
                  BlockColumnSpan span = LazyColumnBlockSet.this.spanProvider.span((int)this.x, this.columnY);
                  if (!span.empty()) {
                     this.z = span.min();
                     this.maximumZ = span.max();
                     this.columnActive = true;
                     break;
                  }
               }
               if (this.columnActive) {
                  continue;
               }
               this.y = LazyColumnBlockSet.this.minimumY;
               this.x++;
            }
            return false;
         }

         private boolean findInActiveColumn() {
            while (this.columnActive && this.z <= this.maximumZ) {
               int candidateZ = (int)this.z++;
               if (LazyColumnBlockSet.this.predicate.test((int)this.x, this.columnY, candidateZ)) {
                  this.next = new BlockPos((int)this.x, this.columnY, candidateZ);
                  return true;
               }
            }
            this.columnActive = false;
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
   interface ColumnSpanProvider {
      BlockColumnSpan span(int x, int y);
   }
}
