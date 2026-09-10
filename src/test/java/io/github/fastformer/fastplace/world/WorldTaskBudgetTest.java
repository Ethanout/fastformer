package io.github.fastformer.fastplace.world;


import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorldTaskBudgetTest {
   @Test
   void slowCellsStillReceiveTheMinimumProgressGuarantee() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testing(4096, 64, 3_000_000L, now::get);
      now.set(10_000_000L);

      while (budget.tryConsume()) {
      }

      assertEquals(64, budget.consumed());
   }

   @Test
   void fastCellsRemainBoundedByTheHardMaximum() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testing(128, 16, 3_000_000L, now::get);

      while (budget.tryConsume()) {
      }

      assertEquals(128, budget.consumed());
   }

   @Test
   void serverTickBudgetAllowsLargeBatchesUntilHardCap() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testing(262144, 1, Long.MAX_VALUE, now::get);

      while (budget.tryConsume()) {
         now.incrementAndGet();
      }

      assertEquals(262144, budget.consumed());
   }

   @Test
   void timeLimitStopsWorkAfterMinimumProgress() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testing(4096, 2, 3_000_000L, now::get);

      assertEquals(true, budget.tryConsume());
      assertEquals(true, budget.tryConsume());
      now.set(3_000_000L);
      assertEquals(false, budget.tryConsume());
   }

   @Test
   void slowPreviousBatchUsesMeasuredThroughput() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testingAdaptive(
         100_000, 1, 1_000_000L, now::get, 100, 10_000_000L
      );

      while (budget.tryConsume()) {
      }

      assertEquals(10, budget.consumed());
   }

   @Test
   void oneExtremelySlowCellStillAllowsMinimumProgress() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testingAdaptive(
         128, 1, 1_000_000L, now::get, 1, 10_000_000L
      );

      while (budget.tryConsume()) {
      }

      assertEquals(1, budget.consumed());
   }

   @Test
   void extremeTimingValuesCannotOverflowThroughputCalculation() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testingAdaptive(
         262144, 1, 1L, now::get, Integer.MAX_VALUE, Long.MAX_VALUE
      );

      while (budget.tryConsume()) {
      }

      assertEquals(1, budget.consumed());
   }

   @Test
   void quickWaitingStepDoesNotThrottleTheNextWritingBatch() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testingAdaptive(
         128, 1, 1_000_000L, now::get, 1, 1L
      );

      while (budget.tryConsume()) {
      }

      assertEquals(128, budget.consumed());
   }

   @Test
   void quickPreviousBatchCannotExceedTheCurrentMemoryLimit() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testingAdaptive(
         100, 1, 1_000_000L, now::get, Integer.MAX_VALUE, 1L
      );

      while (budget.tryConsume()) {
      }

      assertEquals(100, budget.consumed());
   }

   @Test
   void maximumTargetTimeDoesNotOverflowSlowBatchComparison() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testingAdaptive(
         8, 1, Long.MAX_VALUE, now::get, 8, Long.MAX_VALUE
      );

      while (budget.tryConsume()) {
      }

      assertEquals(8, budget.consumed());
   }

   @Test
   void smallOperationFastPathAllowsItsHardCap() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testingSmallOperation(0, 0L, now::get);

      while (budget.tryConsume()) {
      }

      assertEquals(65_536, budget.consumed());
   }

   @Test
   void slowSmallOperationBatchIsReducedToTargetThroughput() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testingSmallOperation(100, 18_000_000L, now::get);

      while (budget.tryConsume()) {
      }

      assertEquals(33, budget.consumed());
   }

   @Test
   void smallOperationFastPathRejectsPressureAndLargeOrInvalidTargets() {
      assertEquals(false, WorldTaskBudget.usesSmallOperationFastPath(true, 1));
      assertEquals(false, WorldTaskBudget.usesSmallOperationFastPath(false, 0));
      assertEquals(false, WorldTaskBudget.usesSmallOperationFastPath(false, -1));
      assertEquals(true, WorldTaskBudget.usesSmallOperationFastPath(false, 32_768));
      assertEquals(false, WorldTaskBudget.usesSmallOperationFastPath(false, 32_769));
   }
}
