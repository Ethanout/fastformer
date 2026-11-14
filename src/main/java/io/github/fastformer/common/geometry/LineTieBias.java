package io.github.fastformer.common.geometry;

/** Selects one of the two single-voxel answers only when a Bresenham error is exactly tied. */
public enum LineTieBias {
   DEFAULT,
   OPPOSITE;

   public boolean advances(long error) {
      return error > 0L || error == 0L && this == DEFAULT;
   }

   public static LineTieBias orDefault(LineTieBias value) {
      return value == null ? DEFAULT : value;
   }
}
