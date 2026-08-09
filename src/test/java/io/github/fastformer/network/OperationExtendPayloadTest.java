package io.github.fastformer.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperationExtendPayloadTest {
   @Test
   void acceptsFaceSelectionAndPointAxes() {
      assertFalse(OperationExtendPayload.validAxis(-1));
      for (int axis = 0; axis <= 8; axis++) {
         assertTrue(OperationExtendPayload.validAxis(axis));
      }
      assertFalse(OperationExtendPayload.validAxis(9));
   }
}
