package io.github.fastformer.server.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.RecoveryJournalTestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldTaskSchedulerTest {
   @AfterEach
   void resetGate() { RecoveryJournalTestAccess.resetWriteGate(); }

   @Test
   void schedulerPhaseFailureStopsTheCallerFromAdvancing() {
      assertTrue(WorldTaskScheduler.tickSafely("test-success", () -> {}));
      assertFalse(WorldTaskScheduler.tickSafely("test-failure", () -> {
         throw new IllegalStateException("expected test failure");
      }));
      assertFalse(PersistentRecoveryJournal.writesAllowed());
   }
}
