package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerInputDispatcherUndoRouteTest {
   @Test
   void unavailableOrBusyBlocksEveryUndoTarget() {
      assertEquals(
         ServerInputDispatcher.UndoRoute.BLOCKED,
         ServerInputDispatcher.undoRoute(false, false, true, true, true)
      );
      assertEquals(
         ServerInputDispatcher.UndoRoute.BLOCKED,
         ServerInputDispatcher.undoRoute(true, true, true, true, true)
      );
   }

   @Test
   void sessionRollbackKeepsPriorityOverWorldTasks() {
      assertEquals(
         ServerInputDispatcher.UndoRoute.SESSION,
         ServerInputDispatcher.undoRoute(true, false, true, true, true)
      );
   }

   @Test
   void activeWritersAreInterruptedBeforeCommittedHistory() {
      assertEquals(
         ServerInputDispatcher.UndoRoute.FAST_PLACE_TASK,
         ServerInputDispatcher.undoRoute(true, false, false, true, false)
      );
      assertEquals(
         ServerInputDispatcher.UndoRoute.OPERATION_TASK,
         ServerInputDispatcher.undoRoute(true, false, false, false, true)
      );
   }

   @Test
   void completedWorldHistoryIsUsedOnlyWithoutAnActiveWriter() {
      assertEquals(
         ServerInputDispatcher.UndoRoute.HISTORY,
         ServerInputDispatcher.undoRoute(true, false, false, false, false)
      );
   }

   @Test
   void activeUncommittedTaskConsumesOneRequestedUndoStep() {
      assertEquals(0, ServerInputDispatcher.remainingUndoAfterTaskCancellation(1));
      assertEquals(2, ServerInputDispatcher.remainingUndoAfterTaskCancellation(3));
      assertEquals(
         WorldHistoryManager.MAX_LIMIT - 1,
         ServerInputDispatcher.remainingUndoAfterTaskCancellation(Integer.MAX_VALUE)
      );
   }
}
