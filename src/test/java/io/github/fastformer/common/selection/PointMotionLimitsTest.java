package io.github.fastformer.common.selection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PointMotionLimitsTest {
   @Test
   void acceptsTheExistingPerAxisStepLimit() {
      assertTrue(PointMotionLimits.accepts(BlockPos.ZERO, new BlockPos(128, -128, 128)));
      assertFalse(PointMotionLimits.accepts(BlockPos.ZERO, new BlockPos(129, 0, 0)));
   }

   @Test
   void rejectsNullAndOverflowingEndpoints() {
      assertFalse(PointMotionLimits.accepts(null, BlockPos.ZERO));
      assertFalse(PointMotionLimits.accepts(BlockPos.ZERO, null));
      assertFalse(PointMotionLimits.accepts(new BlockPos(Integer.MIN_VALUE, 0, 0),
         new BlockPos(Integer.MAX_VALUE, 0, 0)));
   }
}
