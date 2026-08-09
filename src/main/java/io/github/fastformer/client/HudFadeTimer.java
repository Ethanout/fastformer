package io.github.fastformer.client;

/** Keeps short-lived HUD feedback visible before fading it out. */
public final class HudFadeTimer {
   private final long holdNanos;
   private final long fadeNanos;
   private long touchedAt = Long.MIN_VALUE;

   public HudFadeTimer(long holdNanos, long fadeNanos) {
      this.holdNanos = Math.max(0L, holdNanos);
      this.fadeNanos = Math.max(1L, fadeNanos);
   }

   public void touch(long nowNanos) {
      this.touchedAt = nowNanos;
   }

   public void clear() {
      this.touchedAt = Long.MIN_VALUE;
   }

   public int alpha(long nowNanos) {
      if (this.touchedAt == Long.MIN_VALUE || nowNanos < this.touchedAt) {
         return 0;
      }
      long elapsed = nowNanos - this.touchedAt;
      if (elapsed <= this.holdNanos) {
         return 255;
      }
      long fadeElapsed = elapsed - this.holdNanos;
      if (fadeElapsed >= this.fadeNanos) {
         return 0;
      }
      return (int)Math.round(255.0 * (1.0 - (double)fadeElapsed / (double)this.fadeNanos));
   }
}
