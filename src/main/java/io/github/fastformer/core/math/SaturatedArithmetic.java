package io.github.fastformer.core.math;

/** Saturating arithmetic for non-negative work estimates. */
public final class SaturatedArithmetic {
   private SaturatedArithmetic() {
   }

   public static long saturatedMultiply(long left, long right) {
      return left > 0L && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
   }

   public static long saturatedAdd(long left, long right) {
      return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }
}
