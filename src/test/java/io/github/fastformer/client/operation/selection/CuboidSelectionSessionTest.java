package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class CuboidSelectionSessionTest {
   @Test
   void pointsAreInputRecordsAndPushPullUsesCurrentBounds() {
      CuboidSelectionSession session = new CuboidSelectionSession(new BlockPos(2, 2, 2), new BlockPos(4, 4, 4));
      assertTrue(session.pushPull(CuboidSelectionSession.Face.X_POSITIVE, -2));
      assertEquals(new BlockPos(2, 2, 2), session.point1());
      assertEquals(new BlockPos(4, 4, 4), session.point2());
      assertEquals(new BlockPos(2, 2, 2), session.minPoint());
      assertEquals(new BlockPos(2, 4, 4), session.maxPoint());
   }

   @Test
   void expandOnlyGrowsAndLockedSelectionCannotEdit() {
      CuboidSelectionSession session = new CuboidSelectionSession(BlockPos.ZERO, new BlockPos(1, 1, 1));
      assertFalse(session.expand(new BlockPos(1, 1, 1)));
      assertTrue(session.expand(new BlockPos(-2, 3, 0)));
      session.lock();
      assertFalse(session.pushPull(CuboidSelectionSession.Face.Y_POSITIVE, 1));
      assertFalse(session.expand(new BlockPos(10, 10, 10)));
      assertFalse(session.setPoint1(BlockPos.ZERO));
   }

   @Test
   void expandToKeepsInputPointsUnchanged() {
      BlockPos first = new BlockPos(2, 3, 4);
      BlockPos second = new BlockPos(5, 6, 7);
      CuboidSelectionSession session = new CuboidSelectionSession(first, second);

      assertTrue(session.expandTo(new BlockPos(-1, 9, 3)));
      assertEquals(first, session.point1());
      assertEquals(second, session.point2());
      assertEquals(new BlockPos(-1, 3, 3), session.minPoint());
      assertEquals(new BlockPos(5, 9, 7), session.maxPoint());
   }

   @Test
   void pushPullClampsAtIntegerCoordinateLimits() {
      CuboidSelectionSession upper = new CuboidSelectionSession(
         new BlockPos(Integer.MAX_VALUE - 2, 0, 0), new BlockPos(Integer.MAX_VALUE - 1, 0, 0));
      assertTrue(upper.pushPull(CuboidSelectionSession.Face.X_POSITIVE, Integer.MAX_VALUE));
      assertEquals(Integer.MAX_VALUE, upper.maxPoint().getX());
      assertEquals(Integer.MAX_VALUE - 2, upper.minPoint().getX());

      CuboidSelectionSession lower = new CuboidSelectionSession(
         new BlockPos(Integer.MIN_VALUE + 1, 0, 0), new BlockPos(Integer.MIN_VALUE + 2, 0, 0));
      assertTrue(lower.pushPull(CuboidSelectionSession.Face.X_NEGATIVE, Integer.MAX_VALUE));
      assertEquals(Integer.MIN_VALUE, lower.minPoint().getX());
      assertEquals(Integer.MIN_VALUE + 2, lower.maxPoint().getX());
   }
}
