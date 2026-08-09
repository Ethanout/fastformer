package io.github.fastformer.client;

/** Defers a one-step click until release without adding it to a long drag. */
record DeferredDragClick(long pressedAtNanos, int steps) {
   static DeferredDragClick none() {
      return new DeferredDragClick(0L, 0);
   }

   static DeferredDragClick start(long nowNanos, int steps) {
      return new DeferredDragClick(nowNanos, steps);
   }

   boolean awaitingRelease(long nowNanos, long shortPressNanos) {
      return this.steps != 0
         && nowNanos >= this.pressedAtNanos
         && nowNanos - this.pressedAtNanos <= shortPressNanos;
   }

   int releaseSteps(long nowNanos, long shortPressNanos) {
      return this.awaitingRelease(nowNanos, shortPressNanos) ? this.steps : 0;
   }

   boolean shouldAwaitRelease(long nowNanos, long shortPressNanos, int projectedSteps, int sentSteps) {
      return this.awaitingRelease(nowNanos, shortPressNanos) && projectedSteps == sentSteps;
   }

   DeferredDragClick cancel() {
      return none();
   }
}
