package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlacementUpdateModeTest {
   @Test
   void clientOnlyModeActuallySuppressesNeighborReactions() {
      assertEquals(2, PlacementUpdateMode.CLIENT_ONLY.flags() & 2);
      assertEquals(16, PlacementUpdateMode.CLIENT_ONLY.flags() & 16);
      assertEquals(32, PlacementUpdateMode.CLIENT_ONLY.flags() & 32);
      assertEquals(0, PlacementUpdateMode.NORMAL.flags() & 16);
   }
}
