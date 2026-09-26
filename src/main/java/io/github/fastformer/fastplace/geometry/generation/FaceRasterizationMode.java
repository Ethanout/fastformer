package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.world.*;

public enum FaceRasterizationMode {
   POINT_SWEEP,
   GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL,
   NORMAL_PLANE_EXPERIMENTAL;

   public static final FaceRasterizationMode DEFAULT = NORMAL_PLANE_EXPERIMENTAL;

   public FaceRasterizationMode next() {
      // Archived values keep their wire IDs for older settings and regression tests.
      return DEFAULT;
   }
}
