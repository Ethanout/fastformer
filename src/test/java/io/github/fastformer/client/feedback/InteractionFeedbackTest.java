package io.github.fastformer.client.feedback;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.client.feedback.AxisFeedback;
import io.github.fastformer.client.feedback.ScrollFeedbackData;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InteractionFeedbackTest {
   @Test
   void coordinateFeedbackUsesTheConfiguredAxisPalette() {
      InteractionFeedback feedback = new InteractionFeedback(axis -> switch (axis) {
         case X -> 0xFF0000;
         case Y -> 0x00FF00;
         case Z -> 0x0000FF;
      });

      ScrollFeedbackData data = feedback.coordinates(new BlockPos(-2, 4, 9));

      assertEquals(List.of(
         new AxisFeedback("X", "-2", 0xFF0000),
         new AxisFeedback("Y", "4", 0x00FF00),
         new AxisFeedback("Z", "9", 0x0000FF)
      ), data.axes());
      assertEquals("", data.text());
   }

   @Test
   void clearResetsGizmoFeedbackOwnedByTheInteraction() {
      InteractionFeedback feedback = new InteractionFeedback(axis -> 0xFFFFFFFF);
      feedback.noteGizmo(Axis.Y, AxisGizmo.Operation.SCALE, 4, 2.5, 100L);

      feedback.clear();

      assertNull(feedback.lastGizmoAxis());
      assertNull(feedback.lastGizmoOperation());
      assertEquals(0, feedback.lastGizmoSteps());
      assertEquals(0.0, feedback.lastGizmoBaseValue());
   }

   @Test
   void leavingEveryGizmoHandleDoesNotRestoreFadedAlpha() {
      InteractionFeedback feedback = new InteractionFeedback(axis -> 0xFFFFFFFF);
      feedback.noteGizmo(Axis.Y, AxisGizmo.Operation.SCALE, 4, 2.5, 0L);
      Object hovered = "Y:SCALE";

      assertEquals(255, feedback.gizmoAlpha(100_000_000L, hovered, feedback.gizmoDwellMultiplier(hovered, 100_000_000L)));
      assertEquals(0, feedback.gizmoAlpha(600_000_000L, hovered, feedback.gizmoDwellMultiplier(hovered, 600_000_000L)));

      assertEquals(0, feedback.gizmoAlpha(610_000_000L, null, 1.0));
   }

   @Test
   void freshGizmoInputShowsFeedbackAgainAtFullAlpha() {
      InteractionFeedback feedback = new InteractionFeedback(axis -> 0xFFFFFFFF);
      feedback.noteGizmo(Axis.Y, AxisGizmo.Operation.SCALE, 4, 2.5, 0L);
      assertEquals(0, feedback.gizmoAlpha(2_000_000_000L, "Y:SCALE", 1.0));

      feedback.noteGizmo(Axis.Y, AxisGizmo.Operation.SCALE, 5, 2.75, 3_000_000_000L);

      assertEquals(255, feedback.gizmoAlpha(3_500_000_000L, "Y:SCALE", 1.0));
   }

   @Test
   void separateInputOwnersNeverShareFeedbackAndResetClearsItsOwner() {
      var first = new io.github.fastformer.client.input.ClientInputSession();
      var second = new io.github.fastformer.client.input.ClientInputSession();
      first.feedback().noteGizmo(Axis.X, AxisGizmo.Operation.MOVE, 3, 12.0, 100L);

      assertEquals(Axis.X, first.feedback().lastGizmoAxis());
      assertNull(second.feedback().lastGizmoAxis());
      first.reset();
      assertNull(first.feedback().lastGizmoAxis());
      assertEquals(0, first.feedback().gizmoAlpha(100L, null, 1.0));
   }

   @Test
   void nonFiniteGizmoBaselinesUseOperationSpecificDefaults() {
      var feedback = new InteractionFeedback();
      feedback.noteGizmo(Axis.Y, AxisGizmo.Operation.SCALE, 0, Double.NaN, 100L);
      assertEquals(1.0, feedback.lastGizmoBaseValue());
      feedback.noteGizmo(Axis.Y, AxisGizmo.Operation.MOVE, 0, Double.POSITIVE_INFINITY, 200L);
      assertEquals(0.0, feedback.lastGizmoBaseValue());
   }
}
