package io.github.fastformer.fastplace.world;

import java.util.function.LongSupplier;

/** Limits main-thread world work by both cell count and elapsed wall time. */
public final class WorldTaskBudget {
   /** Hard cap per service-tick task; elapsed time remains the normal limiter. */
   private static final int MAX_CELLS = 262144;
   private static final int MIN_CELLS = 1;
   private static final long TARGET_NANOS = 3_000_000L;

   private final int maxCells;
   private final int minCells;
   private final long targetNanos;
   private final long startedAt;
   private final LongSupplier nanoTime;
   private int consumed;
   private boolean firstWriteAllowanceUsed;

   private WorldTaskBudget(int maxCells, int minCells, long targetNanos, LongSupplier nanoTime) {
      this.maxCells = Math.max(1, maxCells);
      this.minCells = Math.clamp(minCells, 0, this.maxCells);
      this.targetNanos = Math.max(0L, targetNanos);
      this.nanoTime = nanoTime;
      this.startedAt = nanoTime.getAsLong();
   }

   public static WorldTaskBudget forServerTick() {
      Runtime runtime = Runtime.getRuntime();
      long used = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
      long max = Math.max(1L, runtime.maxMemory());
      double pressure = (double)used / (double)max;
      int maxCells = pressure >= 0.90D
         ? 2048
         : pressure >= 0.80D
            ? 8192
            : pressure >= 0.70D ? 32768 : MAX_CELLS;
      long targetNanos = pressure >= 0.90D
         ? 1_000_000L
         : pressure >= 0.80D ? 2_000_000L : TARGET_NANOS;
      return new WorldTaskBudget(maxCells, MIN_CELLS, targetNanos, System::nanoTime);
   }

   /** Creates a budget whose hard cap is reduced under a soft memory signal. */
   public static WorldTaskBudget forServerTick(boolean memoryPressure) {
      return forServerTick(memoryPressure, 0, 0L);
   }

   public static WorldTaskBudget forServerTick(boolean memoryPressure, int previousBatchCells, long previousBatchNanos) {
      if (!memoryPressure) {
         return forServerTick().adjustForPreviousBatch(previousBatchCells, previousBatchNanos);
      }
      Runtime runtime = Runtime.getRuntime();
      long used = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
      long max = Math.max(1L, runtime.maxMemory());
      double pressure = (double)used / (double)max;
      int maxCells = pressure >= 0.90D ? 512 : pressure >= 0.80D ? 2048 : 8192;
      long targetNanos = pressure >= 0.90D ? 500_000L : 1_000_000L;
      return new WorldTaskBudget(maxCells, MIN_CELLS, targetNanos, System::nanoTime)
         .adjustForPreviousBatch(previousBatchCells, previousBatchNanos);
   }

   /** Gives small operations a shorter completion time when the heap is healthy. */
   public static WorldTaskBudget forSmallOperation(
      boolean memoryPressure, int operationCells, int previousBatchCells, long previousBatchNanos
   ) {
      if (!usesSmallOperationFastPath(memoryPressure, operationCells)) {
         return forServerTick(memoryPressure, previousBatchCells, previousBatchNanos);
      }
      return smallOperationBudget(previousBatchCells, previousBatchNanos, System::nanoTime);
   }

   static boolean usesSmallOperationFastPath(boolean memoryPressure, int operationCells) {
      return !memoryPressure && operationCells > 0 && operationCells <= 32_768;
   }

   private static WorldTaskBudget smallOperationBudget(
      int previousBatchCells, long previousBatchNanos, LongSupplier nanoTime
   ) {
      return new WorldTaskBudget(65_536, MIN_CELLS, 6_000_000L, nanoTime)
         .adjustForPreviousBatch(previousBatchCells, previousBatchNanos);
   }

   static WorldTaskBudget testingSmallOperation(
      int previousBatchCells, long previousBatchNanos, LongSupplier nanoTime
   ) {
      return smallOperationBudget(previousBatchCells, previousBatchNanos, nanoTime);
   }

   private WorldTaskBudget adjustForPreviousBatch(int previousBatchCells, long previousBatchNanos) {
      if (previousBatchCells <= 0 || previousBatchNanos <= 0L || this.targetNanos <= 0L) {
         return this;
      }
      int adjusted = this.maxCells;
      if (exceedsTwice(previousBatchNanos, this.targetNanos)) {
         adjusted = this.proportionalCellLimit(previousBatchCells, previousBatchNanos);
      }
      return new WorldTaskBudget(adjusted, this.minCells, this.targetNanos, this.nanoTime);
   }

   private int proportionalCellLimit(int previousBatchCells, long previousBatchNanos) {
      double targetRatio = (double)this.targetNanos / (double)previousBatchNanos;
      long proposed = (long)(previousBatchCells * targetRatio);
      return (int)Math.clamp(proposed, (long)this.minCells, (long)this.maxCells);
   }

   private static boolean exceedsTwice(long value, long threshold) {
      return threshold >= 0L && value > threshold && value - threshold > threshold;
   }

   static WorldTaskBudget testing(int maxCells, int minCells, long targetNanos, LongSupplier nanoTime) {
      return new WorldTaskBudget(maxCells, minCells, targetNanos, nanoTime);
   }

   static WorldTaskBudget testingAdaptive(
      int maxCells,
      int minCells,
      long targetNanos,
      LongSupplier nanoTime,
      int previousBatchCells,
      long previousBatchNanos
   ) {
      return new WorldTaskBudget(maxCells, minCells, targetNanos, nanoTime)
         .adjustForPreviousBatch(previousBatchCells, previousBatchNanos);
   }

   public boolean tryConsume() {
      if (!this.hasRemaining()) {
         return false;
      }
      this.consumed++;
      return true;
   }

   public boolean hasRemaining() {
      if (this.consumed >= this.maxCells) {
         return false;
      }
      return this.consumed < this.minCells || this.nanoTime.getAsLong() - this.startedAt < this.targetNanos;
   }

   /** Allows one already-journaled first write after preparation exhausts this budget. */
   public boolean tryConsumeFirstWrite() {
      if (this.firstWriteAllowanceUsed) {
         return false;
      }
      this.firstWriteAllowanceUsed = true;
      this.consumed++;
      return true;
   }

   public int consumed() {
      return this.consumed;
   }
}
