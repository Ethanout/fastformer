package io.github.fastformer.workspace.transform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/** Rotation policies used by normal, alternative and control gestures. */
public final class PixelPerfectAngles {
   private static final double QUARTER_TURN = Math.PI * 0.5;
   private static final List<Double> PIXEL_ANGLES = buildAngles();
   private static final List<Double> SELECTION_ANGLES = buildSelectionAngles();

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

   /** Nearest integer slope up to 64, retaining completed turns. */
   public static double selectionSnap(double radians) {
      double normalized = normalize(radians);
      int index = Collections.binarySearch(SELECTION_ANGLES, normalized);
      if (index >= 0) return radians;
      int insertion = -index - 1;
      double lower = SELECTION_ANGLES.get(Math.floorMod(insertion - 1, SELECTION_ANGLES.size()));
      double upper = SELECTION_ANGLES.get(insertion % SELECTION_ANGLES.size());
      double nearest = angularDistance(normalized, lower) <= angularDistance(normalized, upper) ? lower : upper;
      return radians + normalize(nearest - normalized);
   }

   private static List<Double> buildSelectionAngles() {
      List<Double> angles = new ArrayList<>();
      for (int y = -64; y <= 64; y++) {
         for (int x = -64; x <= 64; x++) {
            if (gcd(Math.abs(x), Math.abs(y)) == 1) angles.add(Math.atan2(y, x));
         }
      }
      angles.sort(Double::compare);
      return List.copyOf(angles);
   }

   private static int gcd(int x, int y) {
      while (y != 0) {
         int remainder = x % y;
         x = y;
         y = remainder;
      }
      return x;
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
