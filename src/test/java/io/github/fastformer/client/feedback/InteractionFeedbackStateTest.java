package io.github.fastformer.client.feedback;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.client.feedback.AxisFeedback;
import io.github.fastformer.client.feedback.ScrollFeedbackData;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InteractionFeedbackStateTest {
   @Test
   void clearResetsGizmoFeedbackAlongsideTransientTimers() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.noteGizmo(Axis.Y, AxisGizmo.Operation.SCALE, 4, 2.5);
      state.gizmo().touch(100L);
      state.gizmoDwell().multiplier("Y:SCALE", 100L);

      state.clear();

      assertNull(state.lastGizmoAxis());
      assertNull(state.lastGizmoOperation());
      assertEquals(0, state.lastGizmoSteps());
      assertEquals(0.0, state.lastGizmoBaseValue());
      assertEquals(0, state.gizmo().alpha(100L));
      assertEquals(1.0, state.gizmoDwell().multiplier("Y:SCALE", 200L));
   }

   @Test
   void clearResetsLatchedFreeScrollSession() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.beginFreeScrollSession(new BlockPos(3, -2, 7));

      state.clear();

      assertEquals(false, state.freeScrollSession());
      assertEquals(BlockPos.ZERO, state.freeScrollOffset());
   }

   @Test
   void freeScrollSessionRetainsLatestOffsetUntilItEnds() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      BlockPos offset = new BlockPos(3, -2, 7);

      state.beginFreeScrollSession(offset);

      assertEquals(true, state.freeScrollSession());
      assertEquals(offset, state.freeScrollOffset());

      state.endFreeScrollSession();

      assertEquals(false, state.freeScrollSession());
      assertEquals(BlockPos.ZERO, state.freeScrollOffset());
   }

   @Test
   void freeScrollSessionSurvivesInactiveWheelSnapshot() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      BlockPos offset = new BlockPos(3, -2, 7);

      assertEquals(true, state.refreshFreeScrollSession(true, true, offset, 100L));
      assertEquals(true, state.refreshFreeScrollSession(false, false, null, 200L));

      assertEquals(true, state.freeScrollSession());
      assertEquals(offset, state.freeScrollOffset());
   }

   @Test
   void activeNonFreeScrollSnapshotEndsLatchedSession() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.refreshFreeScrollSession(true, true, new BlockPos(3, -2, 7), 100L);

      assertEquals(false, state.refreshFreeScrollSession(true, false, null, 200L));

      assertEquals(false, state.freeScrollSession());
      assertEquals(BlockPos.ZERO, state.freeScrollOffset());
   }

   @Test
   void inactiveSnapshotKeepsLabelAfterTransientTimerExpires() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.refreshFreeScrollSession(true, true, new BlockPos(3, -2, 7), 100L);
      state.scroll().touch(1_000_000_000L);

      assertEquals(0, state.scroll().alpha(2_500_000_000L));
      assertEquals(true, state.refreshFreeScrollSession(false, false, null, 2_500_000_000L));
      assertEquals(true, state.freeScrollSession());
   }

   @Test
   void freeScrollDataSurvivesOffsetChangesAndClearsAtSessionBoundary() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      BlockPos offset = new BlockPos(3, -2, 7);
      ScrollFeedbackData data = new ScrollFeedbackData(
         List.of(new AxisFeedback("X", "3", 0xFFFFFFFF)), ""
      );

      state.beginFreeScrollSession(offset);
      state.rememberFreeScrollData(data);
      state.beginFreeScrollSession(offset);

      assertEquals(data, state.freeScrollData());

      state.beginFreeScrollSession(offset.offset(1, 0, 0));
      assertEquals(data, state.freeScrollData());

      state.endFreeScrollSession();
      assertNull(state.freeScrollData());
   }

   @Test
   void repeatedFreeScrollSnapshotsKeepTheVisibleText() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      BlockPos offset = new BlockPos(3, -2, 7);
      ScrollFeedbackData data = new ScrollFeedbackData(
         List.of(new AxisFeedback("X", "3", 0xFFFFFFFF)), ""
      );

      state.refreshFreeScrollSession(true, true, offset, 100L);
      state.rememberFreeScrollData(data);

      assertEquals(true, state.refreshFreeScrollSession(true, true, offset, 200L));
      assertEquals(data, state.freeScrollData());
   }

   @Test
   void wheelTransitionKeepsLabelAcrossTemporaryActiveSnapshot() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      BlockPos offset = new BlockPos(3, -2, 7);
      long wheelAt = 1_000_000_000L;
      state.refreshFreeScrollSession(true, true, offset, wheelAt);
      state.protectFreeScrollTransition(wheelAt);

      assertEquals(true, state.refreshFreeScrollSession(true, false, null, wheelAt + 500_000_000L));
      assertEquals(offset, state.freeScrollOffset());
      assertEquals(false, state.refreshFreeScrollSession(
         true, false, null, wheelAt + InteractionFeedbackState.FREE_SCROLL_TRANSITION_GRACE_NANOS
      ));
   }

   @Test
   void switchingDwellTargetDoesNotRestoreFadedGizmoAlpha() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.gizmo().touch(0L);

      assertEquals(255, gizmoAlpha(state, "A", 100_000_000L));

      assertEquals(0, gizmoAlpha(state, "A", 600_000_000L));
      assertEquals(0, gizmoAlpha(state, "B", 610_000_000L));
   }

   @Test
   void dwellMultiplierIncreaseStillAcceleratesTheSameTarget() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.gizmo().touch(0L);

      // The first read resets the dwell window, so it still uses base speed.
      assertEquals(255, gizmoAlpha(state, "A", 99_000_000L));
      // At 100 ms the multiplier becomes 3. Only time after the read is scaled.
      assertEquals(255, gizmoAlpha(state, "A", 100_000_000L));
      assertEquals(255, gizmoAlpha(state, "A", 400_000_000L));
      // Reading again at 600 ms completes the fade because x3 applied twice.
      assertEquals(0, gizmoAlpha(state, "A", 600_000_000L));
   }

   @Test
   void lostHoverKeepsGizmoFadeProgressAtBaseSpeed() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.gizmo().touch(0L);

      assertEquals(255, gizmoAlpha(state, "A", 100_000_000L));
      assertEquals(0, gizmoAlpha(state, "A", 600_000_000L));

      state.gizmoDwell().clear();

      assertEquals(0, gizmoAlpha(state, "A", 610_000_000L));
   }

   @Test
   void newWheelInputShowsScrollFeedbackAgain() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.scroll().touch(0L);
      assertEquals(255, state.scroll().alpha(0L, 1.0));
      assertEquals(0, state.scroll().alpha(2_000_000_000L, 3.0));

      state.scroll().touch(3_000_000_000L);

      assertEquals(255, state.scroll().alpha(3_500_000_000L, 1.0));
      assertEquals(0, state.scroll().alpha(4_400_000_000L, 1.0));
   }

   @Test
   void multipleSameFrameReadsDoNotAdvanceTheFadeTwice() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.scroll().touch(0L);
      // One frame of 400 ms at x3 consumes 1200 ms: hold 1000 ms plus 200 ms of fade.
      long frame = 400_000_000L;

      int first = state.scroll().alpha(frame, 3.0);

      assertEquals(128, first);
      assertEquals(first, state.scroll().alpha(frame, 3.0));
      assertEquals(first, state.scroll().alpha(frame));
   }

   @Test
   void clearRestoresHiddenGizmoFeedback() {
      InteractionFeedbackState state = new InteractionFeedbackState();
      state.gizmo().touch(0L);
      assertEquals(0, state.gizmo().alpha(2_000_000_000L, 3.0));

      state.clear();

      assertEquals(0, state.gizmo().alpha(2_000_000_000L));
   }

   private static int gizmoAlpha(InteractionFeedbackState state, String target, long now) {
      return state.gizmo().alpha(now, state.gizmoDwell().multiplier(target, now));
   }
}
