package io.github.fastformer.fastplace;

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
   void serverTickBudgetAllowsVanillaFillSizedBatches() {
      AtomicLong now = new AtomicLong();
      WorldTaskBudget budget = WorldTaskBudget.testing(32768, 1, Long.MAX_VALUE, now::get);

      while (budget.tryConsume()) {
         now.incrementAndGet();
      }

      assertEquals(32768, budget.consumed());
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
}
