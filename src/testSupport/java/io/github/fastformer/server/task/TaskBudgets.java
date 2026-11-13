package io.github.fastformer.server.task;

import java.util.function.LongSupplier;

/** Deterministic budgets shared by unit tests and GameTest; excluded from production. */
public final class TaskBudgets {
   private TaskBudgets() {}

   public static WorldTaskBudget fixed(int maxCells, int minCells, long targetNanos, LongSupplier nanoTime) {
      return new WorldTaskBudget(maxCells, minCells, targetNanos, nanoTime);
   }

   public static WorldTaskBudget singleCellBudget() { return fixed(1, 1, 0L, () -> 0L); }

   public static WorldTaskBudget adaptive(int maxCells, int minCells, long targetNanos, LongSupplier nanoTime,
      int previousBatchCells, long previousBatchNanos) {
      return fixed(maxCells, minCells, targetNanos, nanoTime).adjustForPreviousBatch(previousBatchCells, previousBatchNanos);
   }

   public static WorldTaskBudget smallOperation(int previousBatchCells, long previousBatchNanos, LongSupplier nanoTime) {
      return WorldTaskBudget.smallOperationBudget(previousBatchCells, previousBatchNanos, nanoTime);
   }
}
