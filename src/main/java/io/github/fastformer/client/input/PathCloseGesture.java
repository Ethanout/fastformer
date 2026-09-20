package io.github.fastformer.client.input;

import net.minecraft.core.BlockPos;

/** Owns the click pair for one input session's path closure. */
final class PathCloseGesture {
   private static final long DOUBLE_CLICK_NANOS = 350_000_000L;
   private BlockPos previousPoint;
   private boolean previousGeometry;
   private long previousTime;

   boolean press(BlockPos candidate, boolean geometry, long occurredAtNanos) {
      long elapsed = occurredAtNanos - previousTime;
      boolean closes = candidate != null && candidate.equals(previousPoint)
         && geometry == previousGeometry && elapsed >= 0 && elapsed <= DOUBLE_CLICK_NANOS;
      if (closes) {
         reset();
      } else {
         previousPoint = candidate == null ? null : candidate.immutable();
         previousGeometry = geometry;
         previousTime = occurredAtNanos;
      }
      return closes;
   }

   void reset() {
      previousPoint = null;
      previousGeometry = false;
      previousTime = 0L;
   }
}
