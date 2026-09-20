package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlacementUpdateModeTest {
   @Test
   void clientOnlyModeActuallySuppressesNeighborReactions() {
      assertEquals(2, PlacementUpdateMode.CLIENT_ONLY.flags() & 2);
      assertEquals(0, PlacementUpdateMode.CLIENT_ONLY.flags() & 1);
      assertEquals(16, PlacementUpdateMode.CLIENT_ONLY.flags() & 16);
      assertEquals(32, PlacementUpdateMode.CLIENT_ONLY.flags() & 32);
      assertEquals(0, PlacementUpdateMode.NORMAL.flags() & 16);
      assertTrue(PlacementUpdateMode.CLIENT_ONLY.suppressesNeighborUpdates());
      assertFalse(PlacementUpdateMode.NORMAL.suppressesNeighborUpdates());
   }
}
