package io.github.fastformer.client.preview.frame;

/** All visual passes of a frame read the same monotonic timestamp and drawing sheet. */
public record AnimationPhase(long nanos, double elapsedSeconds, int drawingSheet) {
   public AnimationPhase {
      if (!Double.isFinite(elapsedSeconds) || elapsedSeconds < 0 || drawingSheet < 1)
         throw new IllegalArgumentException("Invalid animation phase");
   }

   public int sheet(boolean dynamic) { return dynamic ? drawingSheet : 0; }

   public double dashOffset(double speed, double period) {
      if (!Double.isFinite(speed) || !Double.isFinite(period) || period <= 0)
         throw new IllegalArgumentException("Invalid dash timing");
      double seconds = Math.floorMod(nanos, 10_000_000_000L) / 1_000_000_000.0;
      return seconds * speed % period;
   }

   public float breathPulse(long periodNanos) {
      if (periodNanos <= 0) throw new IllegalArgumentException("Breath period must be positive");
      double phase = Math.floorMod(nanos, periodNanos) / (double)periodNanos;
      return (float)(0.5 - Math.cos(phase * Math.PI * 2.0) * 0.5);
   }
}
