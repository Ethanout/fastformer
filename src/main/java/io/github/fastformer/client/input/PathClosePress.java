package io.github.fastformer.client.input;

import net.minecraft.core.BlockPos;

/** Freezes the target and clock used by the path-close gesture. */
record PathClosePress(boolean hoveredStart, boolean doubleClickEnabled, BlockPos candidate,
   boolean geometry, long occurredAtNanos, io.github.fastformer.network.payload.geometry.ClosePathPayload payload) {
   PathClosePress(boolean hoveredStart, boolean doubleClickEnabled, BlockPos candidate, boolean geometry, long occurredAtNanos) {
      this(hoveredStart, doubleClickEnabled, candidate, geometry, occurredAtNanos, null);
   }
   PathClosePress {
      candidate = candidate == null ? null : candidate.immutable();
   }

   boolean closes(PathCloseGesture gesture) {
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
