package io.github.fastformer.fastplace.selection;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SmartSelectionCellRaycastTest {
   @Test void addingAndRemovingUseOppositeSidesOfTheSameHitFace() {
      var cell = new BlockPos(-4, 8, 12);
      for (var face : net.minecraft.core.Direction.values()) {
         var normal = Vec3.atLowerCornerOf(face.getNormal());
         var hit = SmartSelectionRaycast.pickCells(Set.of(cell), Vec3.atCenterOf(cell).add(normal.scale(3)), normal.scale(-1), 10);
         assertNotNull(hit);
         assertEquals(cell, hit.position());
         assertEquals(cell.relative(face), SmartSelectionRaycast.adjacent(hit));
      }
   }

   @Test void fullCellRayPicksNearestMemberAndLeavesDisconnectedGapOpen() {
      var positions = Set.of(BlockPos.ZERO, new BlockPos(0, 0, 4), new BlockPos(5, 0, 0));
      var view = new Vec3(0, 0, 1);
      var nearest = SmartSelectionRaycast.pickCells(positions, new Vec3(0.5, 0.9, -3), view, 20);
      assertNotNull(nearest);
      assertEquals(BlockPos.ZERO, nearest.position());
      assertEquals(3, nearest.hit().distance(), 0.001);
      assertNull(SmartSelectionRaycast.pickCells(positions, new Vec3(2.5, 0.9, -3), view, 20));
      assertNull(SmartSelectionRaycast.pickCells(positions, new Vec3(0.5, 0.9, -3), view, 2));
   }

   @Test void removingFrontCellExposesTheNextTarget() {
      var next = new BlockPos(0, 0, 4);
      var hit = SmartSelectionRaycast.pickCells(Set.of(next), new Vec3(0.5, 0.5, -3), new Vec3(0, 0, 1), 20);
      assertNotNull(hit);
      assertEquals(next, hit.position());
      assertEquals(7, hit.hit().distance(), 0.001);
   }
}
