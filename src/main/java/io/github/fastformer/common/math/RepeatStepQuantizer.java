package io.github.fastformer.common.math;

/** Whole-cell repeat steps from signed handle travel. */
public final class RepeatStepQuantizer {
   private RepeatStepQuantizer() { }
   public static int copiesForOffset(double offset, int structureExtent) {
      if (!Double.isFinite(offset)) return 0;
      int extent = Math.max(1, structureExtent);
      return extent == 1
         ? offset >= 0.0 ? (int)Math.floor(offset + 0.5) : -(int)Math.floor(-offset + 0.5)
         : (int)(offset / extent);
   }
}
