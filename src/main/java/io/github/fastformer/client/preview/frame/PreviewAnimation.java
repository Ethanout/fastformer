package io.github.fastformer.client.preview.frame;

/** Shared immutable visual time, advanced only by the frame boundary. */
public final class PreviewAnimation {
   private static final FrameAnimationClock CLOCK = new FrameAnimationClock(System.nanoTime());
   private static AnimationPhase current = new AnimationPhase(0, 0, 1);

   private PreviewAnimation() {}

   public static AnimationPhase current() { return current; }

   public static void advance(long now, double fps, int sheets) { current = CLOCK.sample(now, fps, sheets); }
}
