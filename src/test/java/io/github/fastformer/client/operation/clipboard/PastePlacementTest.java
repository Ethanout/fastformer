package io.github.fastformer.client.operation.clipboard;

import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PastePlacementTest {
   private static final OccupiedBlockBounds BOUNDS = new OccupiedBlockBounds(
      new BlockPos(2, 4, 6), new BlockPos(4, 8, 9)
   );

   @Test
   void activeWorkspaceOffsetsByFullWidthOfSmallestAxis() {
      assertEquals(new Vec3(3, 0, 0), PastePlacement.inWorkspace(BOUNDS));
   }

   @Test
   void surfaceAnchorAlignsOppositeFaceCenterExactly() {
      Vec3 hit = new Vec3(20.25, 70.0, -4.75);
      assertEquals(
         new Vec3(16.75, 66.0, -12.75),
         PastePlacement.atSurface(BOUNDS, hit, Direction.UP)
      );
      assertEquals(
         new Vec3(15.25, 63.5, -12.75),
         PastePlacement.atSurface(BOUNDS, hit, Direction.WEST)
      );
   }
}
