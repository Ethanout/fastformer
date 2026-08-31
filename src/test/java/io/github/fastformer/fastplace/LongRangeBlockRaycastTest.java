package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

class LongRangeBlockRaycastTest {
   @Test
   void verticalRaysStopAtTheDimensionPlanes() {
      LongRangeBlockRaycast.LimitDistance upward = LongRangeBlockRaycast.dimensionLimit(-64, 320, 64.0, 1.0);
      LongRangeBlockRaycast.LimitDistance downward = LongRangeBlockRaycast.dimensionLimit(-64, 320, 64.0, -1.0);

      assertEquals(LongRangeBlockRaycast.Limit.DIMENSION_TOP, upward.limit());
      assertEquals(LongRangeBlockRaycast.Limit.DIMENSION_BOTTOM, downward.limit());
      assertTrue(upward.distance() < 256.0 && upward.distance() > 255.0);
      assertTrue(downward.distance() < 128.0 && downward.distance() > 127.0);
   }

   @Test
   void horizontalRaysAreNotArtificiallyHeightLimited() {
      LongRangeBlockRaycast.LimitDistance horizontal = LongRangeBlockRaycast.dimensionLimit(-64, 320, 64.0, 0.0);

      assertEquals(Double.POSITIVE_INFINITY, horizontal.distance());
   }

   @Test
   void collisionInteriorHitUsesTheBlockCellsFirstEntryFace() {
      BlockHitResult interior = new BlockHitResult(
         new Vec3(3.5, 1.5, 1.5), Direction.UP, new BlockPos(3, 1, 1), false
      );

      BlockHitResult entry = LongRangeBlockRaycast.firstCellEntry(
         new Vec3(0.5, 1.5, 1.5), new Vec3(10.5, 1.5, 1.5), interior
      );

      assertEquals(Direction.WEST, entry.getDirection());
      assertEquals(new Vec3(3.0, 1.5, 1.5), entry.getLocation());
   }
}
