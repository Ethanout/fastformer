package io.github.fastformer.client.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientSelectionStateTest {
   @Test
   void stateOrderIsExplicitAndMutuallyExclusive() {
      assertEquals(4, ClientSelectionState.values().length);
      assertEquals(ClientSelectionState.POINTING, ClientSelectionState.valueOf("POINTING"));
      assertEquals(ClientSelectionState.FOCUSED, ClientSelectionState.valueOf("FOCUSED"));
      assertEquals(ClientSelectionState.UNFOCUSED, ClientSelectionState.valueOf("UNFOCUSED"));
      assertEquals(ClientSelectionState.ALT_FOCUSED, ClientSelectionState.valueOf("ALT_FOCUSED"));
   }
}
