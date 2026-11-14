package io.github.fastformer.client.preview.frame;

import io.github.fastformer.core.math.StableHash;

/** Owns drawing-sheet progression; callers provide time and resource-driven settings. */
public final class FrameAnimationClock {
   private final long start;
   private long step = Long.MIN_VALUE;
   private int sheet = 1;
   private int sheets;

   public FrameAnimationClock(long start) { this.start = start; }

   public AnimationPhase sample(long now, double fps, int count) {
      if (!Double.isFinite(fps) || fps < 0) throw new IllegalArgumentException("Invalid drawing frame rate");
      count = Math.max(2, count);
      long nextStep = (long)Math.floor(now / 1.0E9 * fps);
      if (step != nextStep || sheets != count) {
         sheet = nextSheet(sheet, count, nextStep);
         step = nextStep;
         sheets = count;
      }
      return new AnimationPhase(now, Math.max(0, (now - start) / 1.0E9), sheet);
   }

   static int nextSheet(int previous, int count, long step) {
      count = Math.max(2, count);
      boolean validPrevious = previous >= 1 && previous <= count;
      int choice = 1 + (int)Math.floorMod(StableHash.mix(step), (long)(validPrevious ? count - 1 : count));
      return validPrevious && choice >= previous ? choice + 1 : choice;
   }
}
