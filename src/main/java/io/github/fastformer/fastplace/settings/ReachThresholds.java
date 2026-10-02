package io.github.fastformer.fastplace.settings;

/** Distances for entering vanilla input and returning to FastFormer input. */
public record ReachThresholds(int close, int far) {
   public static final int MAX_DISTANCE = 29_999_984;
   public static final ReachThresholds DEFAULT = new ReachThresholds(3, 20);

   public ReachThresholds {
      if (!valid(close, far)) throw new IllegalArgumentException("Reach requires 0 <= close < far <= " + MAX_DISTANCE);
   }

   public static boolean valid(int close, int far) {
      return close >= 0 && close < far && far <= MAX_DISTANCE;
   }

   public boolean vanillaAt(boolean wasVanilla, double distance) {
      return vanillaAt(wasVanilla, distance, far);
   }

   public boolean vanillaAt(boolean wasVanilla, double distance, double currentReach) {
      if (!Double.isFinite(distance) || distance < 0) return false;
      return wasVanilla ? distance <= currentReach : distance < close;
   }
}
