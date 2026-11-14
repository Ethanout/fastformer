package io.github.fastformer.client.preview.style;

import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.common.geometry.ControlPointRole;
import io.github.fastformer.common.geometry.controlpoint.ControlPoint;
import io.github.fastformer.common.geometry.controlpoint.ControlPointFeedback;

/** Point colors per theme. */
public enum ControlPointStyle {
   START(0x0DF2FF, 0.78F, 0x88A2C4, 0.78F),
   CONTROL(0xFFC71F, 0.76F, 0xD9B36A, 0.78F),
   HOVER(0x1FFF4D, 0.92F, 0x9FB87C, 0.92F),
   AUXILIARY(0xEBEBEB, 0.82F, 0xF4F1EA, 0.78F),
   GIZMO(0xFFFFFF, 0.72F, 0xF4F1EA, 0.78F);

   private final GeometryPalette.Color classic;
   private final float classicAlpha;
   private final GeometryPalette.Color humanist;
   private final float humanistAlpha;

   ControlPointStyle(int classic, float classicAlpha, int humanist, float humanistAlpha) {
      this.classic = new GeometryPalette.Color(classic);
      this.classicAlpha = classicAlpha;
      this.humanist = new GeometryPalette.Color(humanist);
      this.humanistAlpha = humanistAlpha;
   }

   public static ControlPointStyle resolve(ControlPoint point) {
      return resolve(point.role(), point.feedback(), point.hovered());
   }

   public static ControlPointStyle resolve(ControlPointRole role, ControlPointFeedback feedback, boolean hovered) {
      if (hovered && feedback.changesOnHover()) return HOVER;
      if (role == null) return AUXILIARY;
      return switch (role) {
         case PRIMARY, CENTER, BASE_CENTER, DIAMETER_A -> START;
         case SECONDARY, RADIUS, DIAMETER_B, BASE_FACE, HEIGHT -> CONTROL;
         case DERIVED_CENTER -> AUXILIARY;
         case GIZMO_HANDLE -> GIZMO;
      };
   }

   public GeometryPalette.Color color() {
      String key = switch (this) {
         case START -> "start";
         case CONTROL -> "control";
         case HOVER -> "hover";
         case AUXILIARY, GIZMO -> "ink";
      };
      return io.github.fastformer.client.preview.style.GeometryPalette.color(key,
         GeometryPalette.humanist() ? this.humanist : this.classic);
   }

   public float red() {
      return color().red();
   }

   public float green() {
      return color().green();
   }

   public float blue() {
      return color().blue();
   }

   public float alpha() {
      return GeometryPalette.humanist() ? this.humanistAlpha : this.classicAlpha;
   }
}
