package io.github.fastformer.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PhysicalPressGateTest {
   @Test
   void allowsOnlyOneActionUntilRelease() {
      PhysicalPressGate gate = new PhysicalPressGate();

      gate.press();
      assertTrue(gate.consume());
      assertFalse(gate.consume());

      gate.release();
      gate.press();
      assertTrue(gate.consume());
   }

   @Test
   void toleratesLogicalEventArrivingBeforeRawPressEvent() {
      PhysicalPressGate gate = new PhysicalPressGate();

      assertTrue(gate.consume());
      gate.press();
      assertFalse(gate.consume());
   }
}
