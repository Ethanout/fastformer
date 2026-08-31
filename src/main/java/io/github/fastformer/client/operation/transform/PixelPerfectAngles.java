package io.github.fastformer.client.operation.transform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Rotation policies used by normal, alternative and control gestures. */
public final class PixelPerfectAngles {
   private static final double QUARTER_TURN = Math.PI * 0.5;
   private static final List<Double> PIXEL_ANGLES = buildAngles();

   private PixelPerfectAngles() {
   }

   public static double defaultSnap(double radians) {
      return Math.rint(radians / QUARTER_TURN) * QUARTER_TURN;
   }

   public static double snap(double radians) {
      double normalized = normalize(radians);
      return PIXEL_ANGLES.stream()
         .min(Comparator.comparingDouble(candidate -> angularDistance(normalized, candidate)))
         .orElse(0.0);
   }

   public static double free(double radians) {
      return radians;
   }

   private static List<Double> buildAngles() {
      List<Double> result = new ArrayList<>();
      for (int y = -10; y <= 10; y++) {
         for (int x = -10; x <= 10; x++) {
            if (x == 0 && y == 0) {
               continue;
            }
            double angle = Math.atan2(y, x);
            if (result.stream().noneMatch(existing -> Math.abs(existing - angle) < 1.0E-12)) {
               result.add(angle);
            }
         }
      }
      result.sort(Double::compare);
      return List.copyOf(result);
   }

   private static double angularDistance(double left, double right) {
      return Math.abs(normalize(left - right));
   }

   private static double normalize(double radians) {
      double result = radians % (Math.PI * 2.0);
      if (result > Math.PI) {
         result -= Math.PI * 2.0;
      } else if (result <= -Math.PI) {
         result += Math.PI * 2.0;
      }
      return result;
   }
}
