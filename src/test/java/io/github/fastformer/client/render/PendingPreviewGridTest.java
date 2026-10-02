package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PendingPreviewGridTest {
   @Test
   void singleBlockKeepsItsTwelveEdges() {
      assertEquals(12, PendingPreviewGrid.build(Set.of(BlockPos.ZERO)).size());
   }

   @Test
   void adjacentBlocksDrawOnlyTheOuterContour() {
      List<PendingPreviewGrid.Segment> grid = PendingPreviewGrid.build(Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)));

      assertEquals(12, grid.size());
      assertEquals(0L, grid.stream().filter(PendingPreviewGridTest::isMiddleTopEdge).count());
   }

   @Test
   void rectangularSlabCollapsesToABox() {
      HashSet<BlockPos> blocks = new HashSet<>();
      for (int x = 0; x < 64; x++) {
         for (int z = 0; z < 64; z++) {
            blocks.add(new BlockPos(x, 0, z));
         }
      }

      List<PendingPreviewGrid.Segment> grid = PendingPreviewGrid.build(blocks);

      assertEquals(12, grid.size());
      assertTrue(grid.stream().anyMatch(segment -> segment.from().equals(new PendingPreviewGrid.Point(0, 1, 0))
         && segment.to().equals(new PendingPreviewGrid.Point(64, 1, 0))));
   }

   @Test
   void stepKeepsTheConcaveCrease() {
      // L-shaped profile: the inner corner edge where the low top meets the riser stays visible.
      List<PendingPreviewGrid.Segment> grid = PendingPreviewGrid.build(Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(0, 1, 0)));

      assertTrue(grid.stream().anyMatch(segment -> segment.from().equals(new PendingPreviewGrid.Point(1, 1, 0))
         && segment.to().equals(new PendingPreviewGrid.Point(1, 1, 1))));
   }

   private static boolean isMiddleTopEdge(PendingPreviewGrid.Segment segment) {
      return segment.from().equals(new PendingPreviewGrid.Point(1, 1, 0))
         && segment.to().equals(new PendingPreviewGrid.Point(1, 1, 1));
   }
}
