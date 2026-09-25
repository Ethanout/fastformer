package io.github.fastformer.client.input;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathClosePressTest {
   @Test
   void capturesMutableCandidateBeforePointerMoves() {
      var point = new BlockPos.MutableBlockPos(1, 2, 3);
      var first = new PathClosePress(false, true, point, false, 100L);
      point.set(4, 5, 6);
      var gesture = new PathCloseGesture();
      assertFalse(first.closes(gesture));
      assertTrue(new PathClosePress(false, true, new BlockPos(1, 2, 3), false, 200L).closes(gesture));
   }

   @Test
   void physicalTimeSeparatesClicksDespiteSameDispatchTick() {
      var gesture = new PathCloseGesture();
      assertFalse(new PathClosePress(false, true, BlockPos.ZERO, false, 100L).closes(gesture));
      assertFalse(new PathClosePress(false, true, BlockPos.ZERO, false, 400_000_100L).closes(gesture));
   }

   @Test
   void disabledClosureClearsPreviousClick() {
      var gesture = new PathCloseGesture();
      assertFalse(new PathClosePress(false, true, BlockPos.ZERO, false, 100L).closes(gesture));
      assertFalse(new PathClosePress(false, false, BlockPos.ZERO, false, 150L).closes(gesture));
      assertFalse(new PathClosePress(false, true, BlockPos.ZERO, false, 200L).closes(gesture));
   }
}
