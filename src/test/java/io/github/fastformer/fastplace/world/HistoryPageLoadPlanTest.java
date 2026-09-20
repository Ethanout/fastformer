package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryPageLoadPlanTest {
   @Test
   void skipsDiskLoadWhenMemoryCanSatisfyTheRequest() {
      UUID newest = UUID.randomUUID();
      HistoryOrderCatalog order = HistoryOrderCatalog.merge(
         List.of(newest), List.of(), List.of(newest), List.of()
      );

      assertTrue(HistoryPageLoadPlan.create(true, 1, List.of(newest), 200, order).isEmpty());
   }

   @Test
   void selectsOnlyDurableBatchesMissingFromMemoryAndKeepsResumeDirection() {
      UUID newest = UUID.randomUUID();
      UUID loaded = UUID.randomUUID();
      UUID older = UUID.randomUUID();
      HistoryOrderCatalog order = HistoryOrderCatalog.merge(
         List.of(newest), List.of(), List.of(newest, loaded, older), List.of()
      );

      HistoryPageLoadPlan plan = HistoryPageLoadPlan.create(true, 2, List.of(newest), 200, order).orElseThrow();

      assertTrue(plan.undoDirection());
      assertEquals(2, plan.requestedCount());
      assertEquals(List.of(loaded, older), plan.operationIds());
   }

   @Test
   void limitsThePageToTheConfiguredHistoryLimit() {
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();
      HistoryOrderCatalog order = HistoryOrderCatalog.merge(
         List.of(), List.of(), List.of(first, second), List.of()
      );

      HistoryPageLoadPlan plan = HistoryPageLoadPlan.create(true, 10, List.of(), 1, order).orElseThrow();

      assertEquals(List.of(first), plan.operationIds());
   }

   @Test
   void selectsRedoBatchesForARedoRequest() {
      UUID redo = UUID.randomUUID();
      HistoryOrderCatalog order = HistoryOrderCatalog.merge(
         List.of(), List.of(), List.of(), List.of(redo)
      );

      HistoryPageLoadPlan plan = HistoryPageLoadPlan.create(false, 1, List.of(), 200, order).orElseThrow();

      assertFalse(plan.undoDirection());
      assertEquals(List.of(redo), plan.operationIds());
   }
}
