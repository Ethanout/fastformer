package io.github.fastformer.core.math;

/** Scalar vector operations that construct the caller's vector type without intermediate objects. */
public final class Vectors {
   private Vectors() {}

   @FunctionalInterface
   public interface Factory<V> {
      V create(double x, double y, double z);
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
