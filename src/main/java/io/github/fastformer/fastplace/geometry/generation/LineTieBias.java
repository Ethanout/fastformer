package io.github.fastformer.fastplace.geometry.generation;

/** Selects one of the two single-voxel answers only when a Bresenham error is exactly tied. */
public enum LineTieBias {
   DEFAULT,
   OPPOSITE;

   boolean advances(long error) {
      return error > 0L || error == 0L && this == DEFAULT;
   }

   static LineTieBias orDefault(LineTieBias value) {
      return value == null ? DEFAULT : value;
   }
}
