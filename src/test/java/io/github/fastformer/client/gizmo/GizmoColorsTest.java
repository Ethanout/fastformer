package io.github.fastformer.client.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.gizmo.HoverFeedback;
import org.junit.jupiter.api.Test;

class GizmoColorsTest {
   @Test void axisAndPointColorsFollowThemeWithoutRebuildingHandles() {
      var previous = GeometryPalette.theme();
      var axis = new AxisGizmo.Handle(AxisGizmo.Operation.MOVE, Axis.X, AxisGizmo.Direction.POSITIVE, null);
      var point = axis.withHoverFeedback(HoverFeedback.pointGizmo(axis.hoverFeedback()));
      try {
         for (var theme : GeometryPalette.Theme.values()) {
            GeometryPalette.selectTheme(theme);
            int base = GeometryPalette.gizmoAxis(Axis.X).rgb();
            assertEquals(base, GizmoColors.handle(axis));
            assertEquals(brighter(base), GizmoColors.handle(axis.withState(true, false)));
            assertEquals(brighter(base), GizmoColors.handle(axis.withState(false, true)));
            assertEquals(base, GizmoColors.handle(point));
            assertEquals(GeometryPalette.gizmoHover().rgb(), GizmoColors.handle(point.withState(true, false)));
            assertEquals(GeometryPalette.gizmoHover().rgb(), GizmoColors.handle(point.withState(false, true)));
         }
      } finally { GeometryPalette.selectTheme(previous); }
   }

   @Test void explicitColorsAndPointOverridesRetainTheirNormalColor() {
      var colors = new HoverFeedback.ExplicitColors(0xFF112233, 0xFFAABBCC, 0x010203);
      var handle = new AxisGizmo.Handle(AxisGizmo.Operation.MOVE, Axis.Y, AxisGizmo.Direction.POSITIVE, null)
         .withHoverFeedback(colors);
      assertEquals(0x112233, GizmoColors.handle(handle));
      assertEquals(0xAABBCC, GizmoColors.handle(handle.withState(true, false)));
      assertEquals(0xAABBCC, GizmoColors.handle(handle.withState(false, true)));
      var point = HoverFeedback.pointGizmo(HoverFeedback.pointGizmo(colors));
      assertSame(colors, ((HoverFeedback.PointColors) point).normal());
      assertEquals(0x112233, GizmoColors.handle(handle.withHoverFeedback(point)));
   }

   private static int brighter(int color) {
      int red = Math.round(((color >>> 16) & 255) * 0.68F + 255 * 0.32F);
      int green = Math.round(((color >>> 8) & 255) * 0.68F + 255 * 0.32F);
      int blue = Math.round((color & 255) * 0.68F + 255 * 0.32F);
      return red << 16 | green << 8 | blue;
   }
}
