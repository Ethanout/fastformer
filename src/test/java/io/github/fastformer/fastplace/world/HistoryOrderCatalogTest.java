package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryOrderCatalogTest {
   @Test
   void memoryEntriesPrecedeOlderDurableEntriesWithoutDuplicates() {
      UUID newest = UUID.randomUUID();
      UUID loaded = UUID.randomUUID();
      UUID older = UUID.randomUUID();

      HistoryOrderCatalog catalog = HistoryOrderCatalog.merge(
         List.of(newest, loaded), List.of(), List.of(loaded, older), List.of()
      );

      assertEquals(List.of(newest, loaded, older), catalog.order(true));
      assertEquals(List.of(older), catalog.unloaded(true, List.of(newest, loaded), 10));
   }

   @Test
   void undoAndRedoMoveTheSameHeadBetweenCompleteOrders() {
      UUID newest = UUID.randomUUID();
      UUID older = UUID.randomUUID();
      HistoryOrderCatalog catalog = HistoryOrderCatalog.merge(
         List.of(), List.of(), List.of(newest, older), List.of()
      );

      catalog.commit(true, newest);
      assertEquals(List.of(older), catalog.order(true));
      assertEquals(List.of(newest), catalog.order(false));

      catalog.commit(false, newest);
      assertEquals(List.of(newest, older), catalog.order(true));
      assertEquals(List.of(), catalog.order(false));
   }

   @Test
   void newOperationClearsRedoAndCountTrimRemovesOnlyOldestIds() {
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();
      UUID redo = UUID.randomUUID();
      UUID newest = UUID.randomUUID();
      HistoryOrderCatalog catalog = HistoryOrderCatalog.merge(
         List.of(), List.of(), List.of(first, second), List.of(redo)
      );

      catalog.addNew(newest);
      catalog.trim(2);

      assertEquals(List.of(newest, first), catalog.order(true));
      assertEquals(List.of(), catalog.order(false));
   }

   @Test
   void commitRejectsADecodedBatchThatIsNotTheDurableHead() {
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();
      HistoryOrderCatalog catalog = HistoryOrderCatalog.merge(
         List.of(), List.of(), List.of(first, second), List.of()
      );

      assertThrows(IllegalStateException.class, () -> catalog.commit(true, second));
   }
}
