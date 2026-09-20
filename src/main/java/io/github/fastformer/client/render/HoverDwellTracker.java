package io.github.fastformer.client.render;

/** Tracks continuous hover time and switches to the fast rate after the dwell threshold. */
public final class HoverDwellTracker {
   private final long rampNanos;
   private final double maximumMultiplier;
   private Object target;
   private long startedAt = Long.MIN_VALUE;

   public HoverDwellTracker(long rampNanos, double maximumMultiplier) {
      this.rampNanos = Math.max(1L, rampNanos);
      this.maximumMultiplier = Double.isFinite(maximumMultiplier)
         ? Math.max(1.0, maximumMultiplier) : 1.0;
   }

   public double multiplier(Object currentTarget, long nowNanos) {
      if (currentTarget == null) {
         clear();
         return 1.0;
      }
      if (!currentTarget.equals(this.target) || this.startedAt == Long.MIN_VALUE || nowNanos < this.startedAt) {
         this.target = currentTarget;
         this.startedAt = nowNanos;
         return 1.0;
      }
      long elapsed = nowNanos - this.startedAt;
      // nanoTime has an arbitrary origin and may be negative; the ordering
      // check above handles clock rollback without treating negative values as invalid.
      return elapsed >= this.rampNanos ? this.maximumMultiplier : 1.0;
   }

   public void clear() {
      this.target = null;
      this.startedAt = Long.MIN_VALUE;
   }
}
