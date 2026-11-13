package io.github.fastformer.server.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.server.history.HistoryService;
import org.junit.jupiter.api.Test;

class HistoryIntentHandlersTest {
   @Test
   void busyHistoryBlocksEveryUndoTarget() {
      assertEquals(
         HistoryIntentHandlers.UndoRoute.BLOCKED,
         HistoryIntentHandlers.undoRoute(true, true, true, true)
      );
   }

   @Test
   void sessionRollbackKeepsPriorityOverWorldTasks() {
      assertEquals(
         HistoryIntentHandlers.UndoRoute.SESSION,
         HistoryIntentHandlers.undoRoute(false, true, true, true)
      );
   }

   @Test
   void activeWritersAreInterruptedBeforeCommittedHistory() {
      assertEquals(
         HistoryIntentHandlers.UndoRoute.FAST_PLACE_TASK,
         HistoryIntentHandlers.undoRoute(false, false, true, false)
      );
      assertEquals(
         HistoryIntentHandlers.UndoRoute.OPERATION_TASK,
         HistoryIntentHandlers.undoRoute(false, false, false, true)
      );
   }

   @Test
   void completedWorldHistoryIsUsedOnlyWithoutAnActiveWriter() {
      assertEquals(
         HistoryIntentHandlers.UndoRoute.HISTORY,
         HistoryIntentHandlers.undoRoute(false, false, false, false)
      );
   }

   @Test
   void activeUncommittedTaskConsumesOneRequestedUndoStep() {
      assertEquals(0, HistoryService.remainingAfterUncommittedUndo(1));
      assertEquals(2, HistoryService.remainingAfterUncommittedUndo(3));
      assertEquals(
         HistoryService.MAX_LIMIT - 1,
         HistoryService.remainingAfterUncommittedUndo(Integer.MAX_VALUE)
      );
   }
}
