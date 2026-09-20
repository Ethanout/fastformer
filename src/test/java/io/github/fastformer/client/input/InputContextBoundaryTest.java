package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InputContextBoundaryTest {
   @Test
   void theWorldContextKeepsAnInFlightGesture() {
      assertTrue(InputContextBoundary.pointerContextIntact(true, false, true));
   }

   @Test
   void anOpenScreenEndsAnInFlightGesture() {
      assertFalse(InputContextBoundary.pointerContextIntact(true, true, true));
   }

   @Test
   void aLostWindowFocusEndsAnInFlightGesture() {
      assertFalse(InputContextBoundary.pointerContextIntact(true, false, false));
   }

   @Test
   void noGestureMeansNoInterruption() {
      assertTrue(InputContextBoundary.pointerContextIntact(false, true, false));
   }
}
