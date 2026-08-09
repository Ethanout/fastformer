package io.github.fastformer.fastplace.geometry;

public final class ScalarAdjuster {
   private ScalarAdjuster() {
   }

   public static int stepped(int value, int steps, int min, int max) {
      return Math.clamp(value + Integer.signum(steps), min, max);
   }

   public static int clampInt(int value, int min, int max) {
      return Math.clamp(value, min, max);
   }

   public static double additive(double value, int steps, double stepSize, double min, double max) {
      if (steps == 0) {
         return value;
      }
      return Math.clamp(value + (double)Integer.signum(steps) * stepSize, min, max);
   }

   public static double multiplicative(double value, int steps, double factor, double min, double max) {
      if (steps == 0) {
         return value;
      }
      double multiplier = steps > 0 ? factor : 1.0 / factor;
      return Math.clamp(value * multiplier, min, max);
   }

   public static int offset(int value, int steps, int min, int max) {
      return Math.clamp(value + steps, min, max);
   }
}
