package io.github.fastformer.common.geometry.generation.volume.column;

import static io.github.fastformer.common.geometry.generation.volume.column.ColumnCoordinates.coordinate;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Immutable set view that expands compact column spans only while iterating. */
final class ColumnBlockSet extends AbstractSet<BlockPos> {
   private final int axis;
   private final int firstKeyAxis;
   private final int secondKeyAxis;
   private final Map<ColumnKey, ColumnSpan> spans;
   private final Set<BlockPos> overlays;
   private final int size;

   ColumnBlockSet(int axis, Map<ColumnKey, ColumnSpan> spans, Set<BlockPos> overlays, long size) {
      if (size < 0L || size > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("Column block count is outside Set limits: " + size);
      }
      this.axis = axis;
      this.firstKeyAxis = (axis + 1) % 3;
      this.secondKeyAxis = (axis + 2) % 3;
      this.spans = Collections.unmodifiableMap(spans);
      this.overlays = Collections.unmodifiableSet(overlays);
      this.size = (int)size;
   }

   @Override
   public int size() {
      return this.size;
   }

   @Override
   public boolean contains(Object candidate) {
      if (!(candidate instanceof BlockPos position)) return false;
      if (this.overlays.contains(position)) return true;
      ColumnSpan span = this.spans.get(new ColumnKey(
         coordinate(position, this.firstKeyAxis), coordinate(position, this.secondKeyAxis)));
      return span != null && span.contains(coordinate(position, this.axis));
   }

   @Override
   public Iterator<BlockPos> iterator() {
      return new ColumnIterator();
   }

   private BlockPos position(ColumnKey key, long coordinate) {
      int[] values = new int[3];
      values[this.axis] = (int)coordinate;
      values[this.firstKeyAxis] = key.first();
      values[this.secondKeyAxis] = key.second();
      return new BlockPos(values[0], values[1], values[2]);
   }

   private final class ColumnIterator implements Iterator<BlockPos> {
      private final Iterator<Map.Entry<ColumnKey, ColumnSpan>> columns = ColumnBlockSet.this.spans.entrySet().iterator();
      private final Iterator<BlockPos> overlayIterator = ColumnBlockSet.this.overlays.iterator();
      private Map.Entry<ColumnKey, ColumnSpan> column;
      private long coordinate;

      @Override
      public boolean hasNext() {
         return hasColumnValue() || this.overlayIterator.hasNext();
      }

      @Override
      public BlockPos next() {
         if (hasColumnValue()) return ColumnBlockSet.this.position(this.column.getKey(), this.coordinate++);
         if (this.overlayIterator.hasNext()) return this.overlayIterator.next();
         throw new NoSuchElementException();
      }

      private boolean hasColumnValue() {
         while (this.column == null || this.coordinate > this.column.getValue().maximum()) {
            if (!this.columns.hasNext()) return false;
            this.column = this.columns.next();
            this.coordinate = this.column.getValue().minimum();
         }
         return true;
      }
   }
}
