package io.github.fastformer.client.input.gesture;

import net.minecraft.core.BlockPos;

/** Freezes the target and clock used by the path-close gesture. */
public record PathClosePress(boolean hoveredStart, boolean doubleClickEnabled, BlockPos candidate,
   boolean geometry, long occurredAtNanos, io.github.fastformer.network.payload.geometry.ClosePathPayload payload) {
   public PathClosePress(boolean hoveredStart, boolean doubleClickEnabled, BlockPos candidate, boolean geometry, long occurredAtNanos) {
      this(hoveredStart, doubleClickEnabled, candidate, geometry, occurredAtNanos, null);
   }
   public PathClosePress {
      candidate = candidate == null ? null : candidate.immutable();
   }

   public boolean closes(PathCloseGesture gesture) {
      if (hoveredStart) {
         gesture.reset();
         return true;
      }
      if (!doubleClickEnabled) {
         gesture.reset();
         return false;
      }
      return gesture.press(candidate, geometry, occurredAtNanos);
   }
}
