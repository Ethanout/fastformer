package io.github.fastformer.server.recovery.gate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecoveryWriteGateTest {
   @Test
   void oneServersFailureDoesNotBlockAnotherServer() {
      var first = new RecoveryWriteGate();
      var second = new RecoveryWriteGate();
      first.blockNewWrites();
      assertFalse(first.writesAllowed());
      assertTrue(second.writesAllowed());
      long recovering = first.beginRecovery();
      assertFalse(first.writesAllowed());
      first.finishRecovery(recovering, true);
      assertTrue(first.writesAllowed());
   }

   @Test
   void failedOrSupersededRecoveryCannotReopenAdmission() {
      var gate = new RecoveryWriteGate();
      long failed = gate.beginRecovery();
      gate.finishRecovery(failed, false);
      assertFalse(gate.writesAllowed());
      long older = gate.beginRecovery();
      long newer = gate.beginRecovery();
      gate.finishRecovery(older, true);
      assertFalse(gate.writesAllowed());
      gate.blockNewWrites();
      gate.finishRecovery(newer, true);
      assertFalse(gate.writesAllowed());
   }

   @Test
   void closePermanentlyRejectsLateRecoveryCompletions() {
      var gate = new RecoveryWriteGate();
      long pending = gate.beginRecovery();
      gate.close();
      gate.close();
      gate.finishRecovery(pending, true);
      assertFalse(gate.writesAllowed());
      gate.finishRecovery(gate.beginRecovery(), true);
      assertFalse(gate.writesAllowed());
   }
}
