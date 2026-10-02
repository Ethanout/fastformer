package io.github.fastformer.fastplace.geometry.controlpoint;

import io.github.fastformer.fastplace.geometry.GeometryPalette;

/** Point colors per theme. */
public enum ControlPointStyle {
   START(0x268CFF, 0.78F, 0xF4F1EA, 0.78F),
   CONTROL(0xFFC71F, 0.76F, 0xD9B36A, 0.78F),
   HOVER(0x1FFF4D, 0.92F, 0xD9B36A, 0.92F),
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

   private GeometryPalette.Color color() {
      String key = switch (this) {
         case START -> "start";
         case CONTROL -> "control";
         case HOVER -> "hover";
         case AUXILIARY, GIZMO -> "ink";
      };
      return io.github.fastformer.fastplace.geometry.GeometryPalette.color(key,
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
