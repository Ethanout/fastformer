package io.github.fastformer.fastplace.selection;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SmartSelectionSearchTest {
   @Test void floodFillUsesSixNeighborsAndHonorsSmallTickSlices() {
      var seed = BlockPos.ZERO;
      var connected = Set.of(seed, seed.east(), seed.east().above());
      var all = new java.util.HashSet<>(connected);
      all.add(seed.west().below());
      var search = new SmartSelectionSearch(seed);
      int ticks = 0;
      while (!search.step(all::contains, 1, Long.MAX_VALUE)) assertTrue(++ticks < 100);
      assertEquals(connected, search.result());
      assertTrue(ticks > 3);
   }

   @Test void exactLimitSucceedsAndOversizeFailsWithoutUnboundedTraversal() {
      var search = new SmartSelectionSearch(BlockPos.ZERO, 3);
      assertThrows(SmartSelectionSearch.LimitExceeded.class,
         () -> { while (!search.step(pos -> true, 2, Long.MAX_VALUE)) { } });
      assertEquals(3, search.size());
      var exact = new SmartSelectionSearch(BlockPos.ZERO, 3);
      var cells = Set.of(BlockPos.ZERO, BlockPos.ZERO.east(), BlockPos.ZERO.east(2));
      while (!exact.step(cells::contains, 2, Long.MAX_VALUE)) { }
      assertEquals(cells, exact.result());
   }

   @Test void expiredDeadlineDoesNotReadTheWorld() {
      var search = new SmartSelectionSearch(BlockPos.ZERO);
      assertFalse(search.step(pos -> { fail("Read after deadline"); return false; }, 100, 0));
      assertEquals(0, search.size());
   }

   @Test void diagonalPiecesRemainOneOwnerAndDistantThresholdIsStrict() {
      assertFalse(SmartSelectionTopology.of(Set.of(BlockPos.ZERO, BlockPos.ZERO.east())).disconnected());
      assertEquals(2, SmartSelectionTopology.of(Set.of(BlockPos.ZERO, new BlockPos(1, 1, 0))).pieces().size());
      assertFalse(SmartSelectionTopology.of(Set.of(BlockPos.ZERO, new BlockPos(64, 0, 0))).distant());
      assertTrue(SmartSelectionTopology.of(Set.of(BlockPos.ZERO, new BlockPos(65, 0, 0))).distant());
      assertTrue(SmartSelectionTopology.of(Set.of(BlockPos.ZERO, new BlockPos(46, 46, 0))).distant());
      assertFalse(SmartSelectionTopology.of(Set.of()).distant());
   }
}
