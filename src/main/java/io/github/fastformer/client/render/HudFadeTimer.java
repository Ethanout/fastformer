package io.github.fastformer.client.render;

/**
 * Keeps short-lived HUD feedback visible before fading it out.
 *
 * <p>The hold and fade lengths keep their meaning per elapsed wall-clock time.
 * A multiplier scales only the time that passes after the previous read, so a
 * multiplier change affects future progress and never rescales time that this
 * timer already consumed. Alpha depends on the complete sequence of reads, not
 * on the read clock alone: repeated reads inside one frame must pass the same
 * {@code nowNanos} so that one frame advances the timer one time.
 */
public final class HudFadeTimer {
   private final long holdNanos;
   private final long fadeNanos;
   private long touchedAt = Long.MIN_VALUE;
   private long lastReadAt = Long.MIN_VALUE;
   private double consumedNanos;

   public HudFadeTimer(long holdNanos, long fadeNanos) {
      this.holdNanos = Math.max(0L, holdNanos);
      this.fadeNanos = Math.max(1L, fadeNanos);
   }

   /** Starts a new display period. Alpha returns to 255 at the next read. */
   public void touch(long nowNanos) {
      this.touchedAt = nowNanos;
      this.lastReadAt = Long.MIN_VALUE;
      this.consumedNanos = 0.0;
   }

   public void clear() {
      this.touchedAt = Long.MIN_VALUE;
      this.lastReadAt = Long.MIN_VALUE;
      this.consumedNanos = 0.0;
   }

   public int alpha(long nowNanos) {
      return alpha(nowNanos, 1.0);
   }

   /**
    * Returns alpha after applying a frame-rate independent elapsed-time multiplier.
    *
    * <p>The interval since the previous read uses the given multiplier. A read that
    * has no previous read inside the current display period uses the multiplier for
    * the whole interval since {@link #touch}, so a one-shot read stays equivalent to
    * the previous elapsed-time calculation. After that, a decrease of the multiplier
    * cannot restore alpha that an earlier read already faded out. A negative
    * interval, which a rewound or non-monotonic clock causes, advances the timer by
    * zero.
    */
   public int alpha(long nowNanos, double elapsedMultiplier) {
      if (this.touchedAt == Long.MIN_VALUE || nowNanos < this.touchedAt) {
         return 0;
      }
      double multiplier = Double.isFinite(elapsedMultiplier) ? Math.max(0.0, elapsedMultiplier) : 1.0;
      if (this.lastReadAt != Long.MIN_VALUE && nowNanos < this.lastReadAt) {
         return alphaForConsumed();
      }
      long deltaNanos = this.lastReadAt == Long.MIN_VALUE
         ? nowNanos - this.touchedAt
         : nowNanos - this.lastReadAt;
      this.lastReadAt = nowNanos;
      if (deltaNanos <= 0L) {
         return alphaForConsumed();
      }
      this.consumedNanos = Math.min(
         (double)this.holdNanos + (double)this.fadeNanos,
         this.consumedNanos + (double)deltaNanos * multiplier
      );
      return alphaForConsumed();
   }

   private int alphaForConsumed() {
      long consumed = (long)this.consumedNanos;
      if (consumed <= this.holdNanos) {
         return 255;
      }
      long fadeElapsed = consumed - this.holdNanos;
      if (fadeElapsed >= this.fadeNanos) {
         return 0;
      }
      return (int)Math.round(255.0 * (1.0 - (double)fadeElapsed / (double)this.fadeNanos));
   }
}
