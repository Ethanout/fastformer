package io.github.fastformer.client.input.drag;

/** Defers a one-step click until release without adding it to a long drag. */
public record DeferredDragClick(long pressedAtNanos, int steps) {
   public static DeferredDragClick none() {
      return new DeferredDragClick(0L, 0);
   }

   public static DeferredDragClick start(long nowNanos, int steps) {
      return new DeferredDragClick(nowNanos, steps);
   }

   public boolean awaitingRelease(long nowNanos, long shortPressNanos) {
      return this.steps != 0
         && nowNanos >= this.pressedAtNanos
         && nowNanos - this.pressedAtNanos <= shortPressNanos;
   }

   public int releaseSteps(long nowNanos, long shortPressNanos) {
      return this.awaitingRelease(nowNanos, shortPressNanos) ? this.steps : 0;
   }

   public boolean shouldAwaitRelease(long nowNanos, long shortPressNanos, int projectedSteps, int sentSteps) {
      return this.awaitingRelease(nowNanos, shortPressNanos) && projectedSteps == sentSteps;
   }

   public DeferredDragClick cancel() {
      return none();
   }
}
