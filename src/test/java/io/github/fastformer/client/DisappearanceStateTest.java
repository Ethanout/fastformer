package io.github.fastformer.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DisappearanceStateTest {
   @Test
   void switchesOnlyAtCapacityAndZero() {
      DisappearanceState state = new DisappearanceState(20);

      for (int tick = 0; tick < 19; tick++) {
         state.tick(true, true);
      }
      assertFalse(state.disappeared());
      assertEquals(0.05F, state.visibility(), 1.0E-6F);
      assertEquals(0.075F, state.visibility(0.5F), 1.0E-6F);

      state.tick(true, true);
      assertTrue(state.disappeared());
      assertEquals(0.0F, state.visibility());
      assertEquals(0.025F, state.visibility(0.5F), 1.0E-6F);

      state.tick(true, false);
      assertTrue(state.disappeared());
      assertEquals(0.05F, state.visibility(), 1.0E-6F);

      for (int tick = 0; tick < 19; tick++) {
         state.tick(true, false);
      }
      assertFalse(state.disappeared());
      assertEquals(1.0F, state.visibility());
   }

   @Test
   void ineligibleTicksDecayInsteadOfFreezing() {
      DisappearanceState state = new DisappearanceState(20);
      for (int tick = 0; tick < 10; tick++) {
         state.tick(true, true);
      }

      state.tick(false, true);

      assertEquals(9, state.count());
      assertEquals(0.55F, state.visibility(), 1.0E-6F);
      assertEquals(0.525F, state.visibility(0.5F), 1.0E-6F);
   }
}
