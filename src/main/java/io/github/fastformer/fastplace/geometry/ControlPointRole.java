package io.github.fastformer.fastplace.geometry;

public enum ControlPointRole {
   PRIMARY(ControlPointStyle.START),
   SECONDARY(ControlPointStyle.CONTROL),
   CENTER(ControlPointStyle.START),
   BASE_CENTER(ControlPointStyle.START),
   RADIUS(ControlPointStyle.CONTROL),
   DIAMETER_A(ControlPointStyle.START),
   DIAMETER_B(ControlPointStyle.CONTROL),
   BASE_FACE(ControlPointStyle.CONTROL),
   HEIGHT(ControlPointStyle.CONTROL),
   DERIVED_CENTER(ControlPointStyle.AUXILIARY),
   GIZMO_HANDLE(ControlPointStyle.GIZMO);

   private final ControlPointStyle style;

   ControlPointRole(ControlPointStyle style) {
      this.style = style;
   }

   public ControlPointStyle style() {
      return this.style;
   }

   public float red() {
      return this.style.red();
   }

   public float green() {
      return this.style.green();
   }

   public float blue() {
      return this.style.blue();
   }

   public float alpha() {
      return this.style.alpha();
   }
}
