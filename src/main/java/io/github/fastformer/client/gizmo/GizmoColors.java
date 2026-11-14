package io.github.fastformer.client.gizmo;

import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.gizmo.HoverFeedback;

/** Resolves shared handle appearance intent against the current client theme. */
public final class GizmoColors {
   private static final float AXIS_HOVER_BRIGHTNESS = 0.32F;

   private GizmoColors() { }

   public static int axis(Axis axis) { return GeometryPalette.gizmoAxis(axis).rgb(); }

   public static int handle(AxisGizmo.Handle handle) {
      return resolve(handle.hoverFeedback(), handle.axis(), handle.hovered(), handle.active());
   }

   private static int resolve(HoverFeedback feedback, Axis axis, boolean hovered, boolean active) {
      return switch (feedback) {
         case HoverFeedback.AxisColors ignored -> hovered || active ? brighten(axis(axis)) : axis(axis);
         case HoverFeedback.PointColors point -> hovered || active
            ? GeometryPalette.gizmoHover().rgb() : resolve(point.normal(), axis, false, false);
         case HoverFeedback.ExplicitColors colors -> active ? colors.activeColor()
            : hovered ? colors.hoverColor() : colors.normalColor();
      };
   }

   private static int brighten(int color) {
      int red = brightenChannel((color >>> 16) & 0xFF);
      int green = brightenChannel((color >>> 8) & 0xFF);
      int blue = brightenChannel(color & 0xFF);
      return (red << 16) | (green << 8) | blue;
   }

   private static int brightenChannel(int channel) {
      return Math.round(channel + (255 - channel) * AXIS_HOVER_BRIGHTNESS);
   }
}
