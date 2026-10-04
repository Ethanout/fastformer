package io.github.fastformer.fastplace.quickshape;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class QuickShapeOverlapTest {
   @Test
   void duplicatedPointsDoNotTurnEveryCandidateIntoAnOverlap() {
      assertFalse(QuickShapeOverlap.overlaps(List.of(BlockPos.ZERO, BlockPos.ZERO), new BlockPos(1, 0, 0)));
      assertTrue(QuickShapeOverlap.overlaps(List.of(BlockPos.ZERO, BlockPos.ZERO), BlockPos.ZERO));
   }

   @Test
   void polygonKeepsItsEditingStageAndUsesAllVerticesOnceClosed() {
      var points = List.of(BlockPos.ZERO, new BlockPos(2, 0, 0), new BlockPos(4, 0, 0), new BlockPos(4, 0, 4));
      assertFalse(QuickShapeOverlap.overlaps(points, BlockPos.ZERO, FaceMode.POLYGON, false));
      assertTrue(QuickShapeOverlap.overlaps(points, new BlockPos(1, 0, 1), FaceMode.POLYGON, true));
      assertFalse(QuickShapeOverlap.overlaps(points, new BlockPos(1, 1, 1), FaceMode.POLYGON, true));
      assertTrue(QuickShapeOverlap.overlaps(points.subList(0, 2), BlockPos.ZERO, FaceMode.POLYGON, false));
   }
   @Test
   void detectsCoincidentPointsAndDegenerateLineAndVolume() {
      var first = BlockPos.ZERO;
      var second = new BlockPos(4, 0, 0);
      var third = new BlockPos(0, 0, 4);
      assertTrue(QuickShapeOverlap.overlaps(List.of(first), first));
      assertFalse(QuickShapeOverlap.overlaps(List.of(first), second));
      assertTrue(QuickShapeOverlap.overlaps(List.of(first, second), new BlockPos(2, 0, 0)));
      assertFalse(QuickShapeOverlap.overlaps(List.of(first, second), third));
      assertTrue(QuickShapeOverlap.overlaps(List.of(first, second, third), new BlockPos(2, 0, 2)));
      assertFalse(QuickShapeOverlap.overlaps(List.of(first, second, third), new BlockPos(2, 1, 2)));
   }
}
