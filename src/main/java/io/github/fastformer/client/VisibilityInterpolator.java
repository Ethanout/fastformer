package io.github.fastformer.client;

/** Smoothly approaches a visible or hidden render state. */
public final class VisibilityInterpolator {
   private final long transitionNanos;
   private double value;
   private long updatedAt = Long.MIN_VALUE;

   public VisibilityInterpolator(long transitionNanos, boolean initiallyVisible) {
      this.transitionNanos = Math.max(1L, transitionNanos);
      this.value = initiallyVisible ? 1.0 : 0.0;
   }

   public float update(boolean visible, long nowNanos) {
      if (this.updatedAt == Long.MIN_VALUE || nowNanos < this.updatedAt) {
         this.updatedAt = nowNanos;
         return (float)this.value;
      }
      long elapsed = nowNanos - this.updatedAt;
      this.updatedAt = nowNanos;
      double target = visible ? 1.0 : 0.0;
      double step = Math.min(1.0, (double)elapsed / (double)this.transitionNanos);
      if (this.value < target) {
         this.value = Math.min(target, this.value + step);
      } else if (this.value > target) {
         this.value = Math.max(target, this.value - step);
      }
      return (float)this.value;
   }

   public void reset(boolean visible) {
      this.value = visible ? 1.0 : 0.0;
      this.updatedAt = Long.MIN_VALUE;
   }
}
