package io.github.fastformer.client.input;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCloseGestureTest {
   @Test
   void matchingPhysicalClicksCloseOnce() {
      PathCloseGesture gesture = new PathCloseGesture();
      assertFalse(gesture.press(BlockPos.ZERO, false, 1));
      assertTrue(gesture.press(BlockPos.ZERO, false, 350_000_001));
      assertFalse(gesture.press(BlockPos.ZERO, false, 350_000_002));
   }

   @Test
   void timeoutAndOutOfOrderClicksDoNotClose() {
      PathCloseGesture gesture = new PathCloseGesture();
      assertFalse(gesture.press(BlockPos.ZERO, false, 10));
      assertFalse(gesture.press(BlockPos.ZERO, false, 9));
      assertFalse(gesture.press(BlockPos.ZERO, false, 350_000_010));
   }

   @Test
   void differentTargetsAndSessionKindsDoNotPair() {
      PathCloseGesture gesture = new PathCloseGesture();
      assertFalse(gesture.press(BlockPos.ZERO, false, 10));
      assertFalse(gesture.press(BlockPos.ZERO, true, 11));
      assertFalse(gesture.press(BlockPos.ZERO.above(), true, 12));
      assertFalse(gesture.press(null, true, 13));
      assertFalse(gesture.press(BlockPos.ZERO.above(), true, 14));
   }

   @Test
   void capturedPointDoesNotFollowMutableCandidate() {
      PathCloseGesture gesture = new PathCloseGesture();
      BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();
      assertFalse(gesture.press(candidate, false, 10));
      candidate.set(1, 2, 3);
      assertTrue(gesture.press(BlockPos.ZERO, false, 11));
   }

   @Test
   void resetAndSeparateOwnersCannotReuseClick() {
      PathCloseGesture first = new PathCloseGesture();
      PathCloseGesture second = new PathCloseGesture();
      assertFalse(first.press(BlockPos.ZERO, false, 10));
      assertFalse(second.press(BlockPos.ZERO, false, 11));
      first.reset();
      assertFalse(first.press(BlockPos.ZERO, false, 12));
   }
}
