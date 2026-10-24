package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class HistoryBoundsTest {
   @Test
   void rejectsInvalidBoundsAndAvoidsOverflowAtCoordinateLimits() {
      assertThrows(IllegalArgumentException.class, () -> new HistoryBounds(1, 0, 0, 0, 1, 1));
      assertThrows(IllegalArgumentException.class, () -> HistoryBounds.of(new long[0], 0, 0, 0, 0, 0, 0));
      assertThrows(IllegalArgumentException.class, () -> HistoryBounds.of(null, 0, 0, 0, 0, 1, 1));
      assertThrows(ArithmeticException.class, () -> HistoryBounds.of(null, Integer.MAX_VALUE, 0, 0, 2, 1, 1));
      var left = new HistoryBounds(Integer.MIN_VALUE, 0, 0, Integer.MIN_VALUE, 0, 0);
      var right = new HistoryBounds(Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0);
      assertFalse(left.within(right, 5));
      assertFalse(right.within(left, 5));
   }

   @Test
   void sparseAndDenseCoordinatesProduceTheSameBounds() {
      var sparse = HistoryBounds.of(new long[]{BlockPos.asLong(-3, 2, 8), BlockPos.asLong(1, 5, 9)}, 0, 0, 0, 0, 0, 0);
      var dense = HistoryBounds.of(null, -3, 2, 8, 5, 4, 2);
      assertEquals(new HistoryBounds(-3, 2, 8, 1, 5, 9), sparse);
      assertEquals(sparse, dense);
   }

   @Test
   void savedUnionSurvivesReloadAndInvalidCachesFallBackToRecordedCells() {
      var cells = new HistoryBounds(1, 2, 3, 4, 5, 6);
      var union = cells.union(new HistoryBounds(-5, 0, 1, -3, 1, 2));
      var root = new CompoundTag();
      union.encode(root);
      assertEquals(union, HistoryBounds.decode(root, cells));
      for (int[] invalid : new int[][]{ {}, {1}, {4, 5, 6, 1, 2, 3}, {2, 2, 3, 4, 5, 6} }) {
         root.putIntArray("HistoryBounds", invalid);
         assertSame(cells, HistoryBounds.decode(root, cells));
      }
      root.putString("HistoryBounds", "invalid");
      assertSame(cells, HistoryBounds.decode(root, cells));
      root.remove("HistoryBounds");
      assertSame(cells, HistoryBounds.decode(root, cells));
   }
}
