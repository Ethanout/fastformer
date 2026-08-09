package io.github.fastformer.fastplace;

import java.util.function.LongSupplier;

/** Limits main-thread world work by both cell count and elapsed wall time. */
final class WorldTaskBudget {
   /** Match the vanilla fill command's 32K single-operation working set. */
   private static final int MAX_CELLS = 32768;
   private static final int MIN_CELLS = 1;
   private static final long TARGET_NANOS = 3_000_000L;

   private final int maxCells;
   private final int minCells;
   private final long targetNanos;
   private final long startedAt;
   private final LongSupplier nanoTime;
   private int consumed;

   private WorldTaskBudget(int maxCells, int minCells, long targetNanos, LongSupplier nanoTime) {
      this.maxCells = Math.max(1, maxCells);
      this.minCells = Math.clamp(minCells, 0, this.maxCells);
      this.targetNanos = Math.max(0L, targetNanos);
      this.nanoTime = nanoTime;
      this.startedAt = nanoTime.getAsLong();
   }

   static WorldTaskBudget forServerTick() {
      return new WorldTaskBudget(MAX_CELLS, MIN_CELLS, TARGET_NANOS, System::nanoTime);
   }

   static WorldTaskBudget testing(int maxCells, int minCells, long targetNanos, LongSupplier nanoTime) {
      return new WorldTaskBudget(maxCells, minCells, targetNanos, nanoTime);
   }

   boolean tryConsume() {
      if (!this.hasRemaining()) {
         return false;
      }
      this.consumed++;
      return true;
   }

   boolean hasRemaining() {
      if (this.consumed >= this.maxCells) {
         return false;
      }
      return this.consumed < this.minCells || this.nanoTime.getAsLong() - this.startedAt < this.targetNanos;
   }

   int consumed() {
      return this.consumed;
   }
}
