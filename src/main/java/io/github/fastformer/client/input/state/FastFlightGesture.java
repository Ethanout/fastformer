package io.github.fastformer.client.input.state;

/** A second forward press owns the boost until release or loss of flight control. */
public final class FastFlightGesture {
   public static final long DOUBLE_PRESS_NANOS = 250_000_000L;
   public static final long ACCELERATION_NANOS = 3_000_000_000L;
   private long firstPress = Long.MIN_VALUE;
   private long boostStarted;
   private boolean held;
   private boolean active;

   public void press(long now) {
      if (held) return;
      held = true;
      if (firstPress != Long.MIN_VALUE && now >= firstPress && now - firstPress <= DOUBLE_PRESS_NANOS) {
         active = true;
         boostStarted = now;
         firstPress = Long.MIN_VALUE;
      } else {
         firstPress = now;
      }
   }

   public void release() {
      held = false;
      active = false;
   }

   public double multiplier(long now) {
      if (!active) return 1.0;
      double progress = Math.clamp((double)(now - boostStarted) / ACCELERATION_NANOS, 0.0, 1.0);
      // Smoothstep eases into acceleration and the 10x cap without overshooting.
      return 2.0 + 8.0 * progress * progress * (3.0 - 2.0 * progress);
   }

   public void reset() {
      release();
      firstPress = Long.MIN_VALUE;
   }
}
