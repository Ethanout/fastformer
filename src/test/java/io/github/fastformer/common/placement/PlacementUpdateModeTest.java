package io.github.fastformer.common.placement;


import io.github.fastformer.common.selection.session.*;
import io.github.fastformer.common.geometry.workflow.*;
import io.github.fastformer.common.geometry.workflow.plan.*;

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
