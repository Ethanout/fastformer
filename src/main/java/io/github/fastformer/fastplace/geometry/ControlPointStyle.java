package io.github.fastformer.fastplace.geometry;

public enum ControlPointStyle {
   START(0.05F, 0.95F, 1.0F, 0.78F),
   CONTROL(1.0F, 0.78F, 0.12F, 0.76F),
   HOVER(0.12F, 1.0F, 0.3F, 0.92F),
   AUXILIARY(0.92F, 0.92F, 0.92F, 0.82F),
   GIZMO(1.0F, 1.0F, 1.0F, 0.72F);

   private final float red;
   private final float green;
   private final float blue;
   private final float alpha;

   ControlPointStyle(float red, float green, float blue, float alpha) {
      this.red = red;
      this.green = green;
      this.blue = blue;
      this.alpha = alpha;
   }

   public float red() {
      return this.red;
   }

   public float green() {
      return this.green;
   }

   public float blue() {
      return this.blue;
   }

   public float alpha() {
      return this.alpha;
   }
}
