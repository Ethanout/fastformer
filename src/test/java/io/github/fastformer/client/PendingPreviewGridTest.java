package io.github.fastformer.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PendingPreviewGridTest {
   @Test
   void singleBlockKeepsItsCompleteTwelveEdgeGrid() {
      assertEquals(12, PendingPreviewGrid.build(Set.of(BlockPos.ZERO)).size());
   }

   @Test
   void adjacentBlocksKeepTheVisibleSeamWithoutDuplicatingIt() {
      List<PendingPreviewGrid.Segment> grid = PendingPreviewGrid.build(Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)));

      assertEquals(16, grid.size());
      assertEquals(1L, grid.stream().filter(PendingPreviewGridTest::isMiddleTopEdge).count());
   }

   @Test
   void rectangularSurfaceRunsAreMergedInsteadOfSampledAway() {
      HashSet<BlockPos> blocks = new HashSet<>();
      for (int x = 0; x < 64; x++) {
         for (int z = 0; z < 64; z++) {
            blocks.add(new BlockPos(x, 0, z));
         }
      }

      List<PendingPreviewGrid.Segment> grid = PendingPreviewGrid.build(blocks);

      assertEquals(516, grid.size());
      assertEquals(true, grid.stream().anyMatch(segment -> segment.from().equals(new PendingPreviewGrid.Point(0, 1, 32))
         && segment.to().equals(new PendingPreviewGrid.Point(64, 1, 32))));
   }

   private static boolean isMiddleTopEdge(PendingPreviewGrid.Segment segment) {
      return segment.from().equals(new PendingPreviewGrid.Point(1, 1, 0))
         && segment.to().equals(new PendingPreviewGrid.Point(1, 1, 1));
   }
}
