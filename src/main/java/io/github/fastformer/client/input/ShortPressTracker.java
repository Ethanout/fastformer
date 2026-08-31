package io.github.fastformer.client.input;

final class ShortPressTracker {
   private long pressedAt = Long.MIN_VALUE;

   void press(long now) {
      this.pressedAt = now;
   }

   boolean release(long now, long threshold) {
      if (this.pressedAt == Long.MIN_VALUE) {
         return false;
      }
      long duration = now - this.pressedAt;
      this.pressedAt = Long.MIN_VALUE;
      return duration >= 0L && duration <= Math.max(0L, threshold);
   }

   void cancel() {
      this.pressedAt = Long.MIN_VALUE;
   }
}
