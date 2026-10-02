package io.github.fastformer.fastplace.geometry.raycast;

import io.github.fastformer.fastplace.settings.ReachThresholds;

/** Keeps the input owner stable between the two distance thresholds. */
public final class ReachTransition {
   private boolean vanilla;

   public boolean update(double distance, ReachThresholds thresholds) {
      vanilla = thresholds.vanillaAt(vanilla, distance);
      return vanilla;
   }

   public boolean update(double distance, ReachThresholds thresholds, double currentReach) {
      vanilla = thresholds.vanillaAt(vanilla, distance, currentReach);
      return vanilla;
   }

   public boolean vanilla() { return vanilla; }
   public void reset() { vanilla = false; }
}
