package io.github.fastformer.common.geometry.generation.volume.column;

import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Boundary view that filters a compact solid without retaining its expanded positions. */
final class BoundaryColumnBlockSet extends AbstractSet<BlockPos> {
   private final ColumnBlockSet solid;
   private final int size;

   private BoundaryColumnBlockSet(ColumnBlockSet solid, int size) {
      this.solid = solid;
      this.size = size;
   }

   static BoundaryColumnBlockSet create(ColumnBlockSet solid, int maxBlocks, BlockGenerationObserver observer) {
      int count = 0;
      for (BlockPos position : solid) {
         observer.checkCancelled();
         if (isBoundary(solid, position) && ++count > maxBlocks) return null;
      }
      return new BoundaryColumnBlockSet(solid, count);
   }

   @Override
   public int size() {
      return this.size;
   }

   @Override
   public boolean contains(Object candidate) {
      return candidate instanceof BlockPos position
         && this.solid.contains(position) && isBoundary(this.solid, position);
   }

   @Override
   public Iterator<BlockPos> iterator() {
      return new BoundaryIterator();
   }

   private static boolean isBoundary(Set<BlockPos> solid, BlockPos position) {
      for (int[] step : ColumnCoordinates.FACE_STEPS) {
         BlockPos neighbor = ColumnCoordinates.neighbor(position, step);
         if (neighbor == null || !solid.contains(neighbor)) return true;
      }
      return false;
   }

   private final class BoundaryIterator implements Iterator<BlockPos> {
      private final Iterator<BlockPos> candidates = BoundaryColumnBlockSet.this.solid.iterator();
      private BlockPos next;

      @Override
      public boolean hasNext() {
         while (this.next == null && this.candidates.hasNext()) {
            BlockPos candidate = this.candidates.next();
            if (isBoundary(BoundaryColumnBlockSet.this.solid, candidate)) this.next = candidate;
         }
         return this.next != null;
      }

      @Override
      public BlockPos next() {
         if (!this.hasNext()) throw new NoSuchElementException();
         BlockPos result = this.next;
         this.next = null;
         return result;
      }
   }
}
