package io.github.fastformer.fastplace;

public enum FaceRasterizationMode {
   POINT_SWEEP,
   GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL;

   public FaceRasterizationMode next() {
      FaceRasterizationMode[] values = values();
      return values[(this.ordinal() + 1) % values.length];
   }
}
