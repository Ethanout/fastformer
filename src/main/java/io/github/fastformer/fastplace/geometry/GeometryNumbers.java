package io.github.fastformer.fastplace.geometry;

import java.util.Locale;
import net.minecraft.world.phys.Vec3;

public final class GeometryNumbers {
   private static final double ZERO_EPSILON = 1.0E-10;

   private GeometryNumbers() {
   }

   public static double cleanZero(double value) {
      return value == 0.0 || Math.abs(value) < ZERO_EPSILON ? 0.0 : value;
   }

   public static double finiteOr(double value, double fallback) {
      return Double.isFinite(value) ? cleanZero(value) : fallback;
   }

   public static boolean finite(double... values) {
      if (values == null) {
         return false;
      }
      for (double value : values) {
         if (!Double.isFinite(value)) {
            return false;
         }
      }
      return true;
   }

   public static Vec3 cleanZero(Vec3 value) {
      if (value == null) {
         return Vec3.ZERO;
      }
      return new Vec3(cleanZero(value.x), cleanZero(value.y), cleanZero(value.z));
   }

   public static Vec3 finiteOrZero(Vec3 value) {
      if (value == null) {
         return Vec3.ZERO;
      }
      return new Vec3(finiteOr(value.x, 0.0), finiteOr(value.y, 0.0), finiteOr(value.z, 0.0));
   }

   public static double[] cleanZero(double[] values) {
      if (values == null) {
         return null;
      }
      double[] result = values.clone();
      for (int i = 0; i < result.length; i++) {
         result[i] = cleanZero(result[i]);
      }
      return result;
   }

   public static double[] finiteOr(double[] values, double[] fallback) {
      if (values == null || fallback == null || values.length != fallback.length) {
         return fallback == null ? null : fallback.clone();
      }
      double[] result = values.clone();
      for (int i = 0; i < result.length; i++) {
         result[i] = finiteOr(result[i], fallback[i]);
      }
      return result;
   }

   public static String fixed(double value, int decimals) {
      int safeDecimals = Math.clamp(decimals, 0, 12);
      double factor = Math.pow(10.0, safeDecimals);
      double rounded = cleanZero(Math.round(finiteOr(value, 0.0) * factor) / factor);
      return String.format(Locale.ROOT, "%." + safeDecimals + "f", rounded);
   }
}
