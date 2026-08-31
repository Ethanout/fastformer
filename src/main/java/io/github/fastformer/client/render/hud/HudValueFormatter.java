package io.github.fastformer.client.render.hud;

import io.github.fastformer.fastplace.geometry.GeometryNumbers;

/** Formats compact numeric values displayed beside the crosshair. */
public final class HudValueFormatter {
   private static final double EPSILON = 1.0E-7;

   private HudValueFormatter() {
   }

   public static String coordinate(double value) {
      return finiteNumber(value, 0.0, 1);
   }

   public static String scale(double value) {
      return finiteNumber(value, 1.0, 2);
   }

   private static String finiteNumber(double value, double fallback, int decimalPlaces) {
      double rounded = Math.rint(GeometryNumbers.finiteOr(value, fallback));
      return Math.abs(value - rounded) < EPSILON
         ? GeometryNumbers.fixed(rounded, 0)
         : GeometryNumbers.fixed(value, decimalPlaces);
   }
}
