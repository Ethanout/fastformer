package io.github.fastformer.server.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.server.recovery.gate.RecoveryWriteGate;
import org.junit.jupiter.api.Test;

class WorldTaskSchedulerTest {
   @Test
   void schedulerPhaseFailureStopsTheCallerFromAdvancing() {
      var gate = new RecoveryWriteGate();
      var otherServerGate = new RecoveryWriteGate();
      assertTrue(WorldTaskScheduler.tickSafely(gate, "test-success", () -> {}));
      assertFalse(WorldTaskScheduler.tickSafely(gate, "test-failure", () -> {
         throw new IllegalStateException("expected test failure");
      }));
      assertFalse(gate.writesAllowed());
      assertTrue(otherServerGate.writesAllowed());
   }
}
