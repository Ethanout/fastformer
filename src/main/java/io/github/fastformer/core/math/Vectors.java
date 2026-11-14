package io.github.fastformer.core.math;

/** Scalar vector operations that construct the caller's vector type without intermediate objects. */
public final class Vectors {
   private Vectors() {}

   @FunctionalInterface
   public interface Factory<V> {
      V create(double x, double y, double z);
   }

   /** Largest absolute component; equal magnitudes prefer X, then Y, then Z. */
   public static Axis dominantAxis(double x, double y, double z) {
      double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
      return ax >= ay && ax >= az ? Axis.X : ay >= az ? Axis.Y : Axis.Z;
   }

   public static double component(double x, double y, double z, Axis axis) {
      return switch (axis) {
         case X -> x;
         case Y -> y;
         case Z -> z;
      };
   }

   public static <V> V axisVector(Axis axis, double amount, Factory<V> factory) {
      return switch (axis) {
         case X -> factory.create(amount, 0.0, 0.0);
         case Y -> factory.create(0.0, amount, 0.0);
         case Z -> factory.create(0.0, 0.0, amount);
      };
   }

   /** Compares length, not length squared. Non-finite coordinates keep their IEEE arithmetic. */
   public static <V> V normalize(double x, double y, double z, double minimumLength, Factory<V> factory) {
      requirePositiveCutoff(minimumLength);
      double length = Math.sqrt(x * x + y * y + z * z);
      return length < minimumLength ? factory.create(0, 0, 0) : scaled(x, y, z, length, factory);
   }

   /** Keeps squared-length cutoff comparisons exact, including the cutoff boundary. */
   public static <V> V normalizeSquared(double x, double y, double z, double minimumLengthSquared, Factory<V> factory) {
      requirePositiveCutoff(minimumLengthSquared);
      double lengthSquared = x * x + y * y + z * z;
      if (lengthSquared < minimumLengthSquared) return factory.create(0, 0, 0);
      double length = Math.sqrt(lengthSquared);
      return factory.create(x / length, y / length, z / length);
   }

   private static <V> V scaled(double x, double y, double z, double length, Factory<V> factory) {
      double scale = 1.0 / length;
      return factory.create(x * scale, y * scale, z * scale);
   }

   private static void requirePositiveCutoff(double cutoff) {
      if (!(cutoff > 0) || !Double.isFinite(cutoff)) {
         throw new IllegalArgumentException("Vector cutoff must be positive and finite");
      }
   }
}
