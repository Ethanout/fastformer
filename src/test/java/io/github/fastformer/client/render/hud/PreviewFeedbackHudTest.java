package io.github.fastformer.client.render.hud;

import io.github.fastformer.client.render.model.AxisFeedback;
import io.github.fastformer.client.render.model.ScrollFeedbackData;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PreviewFeedbackHudTest {
   @Test
   void coordinateFeedbackUsesTheConfiguredAxisPalette() {
      PreviewFeedbackHud feedback = new PreviewFeedbackHud(axis -> switch (axis) {
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
   void clearResetsGizmoFeedbackOwnedByTheHudComponent() {
      PreviewFeedbackHud feedback = new PreviewFeedbackHud(axis -> 0xFFFFFFFF);
      feedback.noteGizmo(AxisGizmo.Axis.Y, AxisGizmo.Operation.SCALE, 4, 2.5, 100L);

      feedback.clear();

      assertNull(feedback.lastGizmoAxis());
      assertNull(feedback.lastGizmoOperation());
      assertEquals(0, feedback.lastGizmoSteps());
      assertEquals(0.0, feedback.lastGizmoBaseValue());
   }

   @Test
   void leavingEveryGizmoHandleDoesNotRestoreFadedAlpha() {
      PreviewFeedbackHud feedback = new PreviewFeedbackHud(axis -> 0xFFFFFFFF);
      feedback.noteGizmo(AxisGizmo.Axis.Y, AxisGizmo.Operation.SCALE, 4, 2.5, 0L);
      Object hovered = "Y:SCALE";

      assertEquals(255, feedback.gizmoAlpha(100_000_000L, hovered, feedback.gizmoDwellMultiplier(hovered, 100_000_000L)));
      assertEquals(0, feedback.gizmoAlpha(600_000_000L, hovered, feedback.gizmoDwellMultiplier(hovered, 600_000_000L)));

      assertEquals(0, feedback.gizmoAlpha(610_000_000L, null, 1.0));
   }

   @Test
   void freshGizmoInputShowsFeedbackAgainAtFullAlpha() {
      PreviewFeedbackHud feedback = new PreviewFeedbackHud(axis -> 0xFFFFFFFF);
      feedback.noteGizmo(AxisGizmo.Axis.Y, AxisGizmo.Operation.SCALE, 4, 2.5, 0L);
      assertEquals(0, feedback.gizmoAlpha(2_000_000_000L, "Y:SCALE", 1.0));

      feedback.noteGizmo(AxisGizmo.Axis.Y, AxisGizmo.Operation.SCALE, 5, 2.75, 3_000_000_000L);

      assertEquals(255, feedback.gizmoAlpha(3_500_000_000L, "Y:SCALE", 1.0));
   }
}
