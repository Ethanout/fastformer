package io.github.fastformer.client.input;

import io.github.fastformer.client.input.DisappearanceState;
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

   @Test
   void acceleratesAfterTwoTicksOnTheSameTarget() {
      DisappearanceState state = new DisappearanceState(20);

      state.tick(true, true, "stone");
      state.tick(true, true, "stone");
      state.tick(true, true, "stone");

      assertTrue(state.accelerated());
      assertEquals(5, state.count());
   }

   @Test
   void switchingTargetRestoresNormalRate() {
      DisappearanceState state = new DisappearanceState(20);

      state.tick(true, true, "stone");
      state.tick(true, true, "stone");
      state.tick(true, true, "stone");
      state.tick(true, true, "dirt");

      assertFalse(state.accelerated());
      assertEquals(6, state.count());
   }

   @Test
   void stableTargetAcceleratesVisibilityRecoveryOutsideRange() {
      DisappearanceState state = new DisappearanceState(20);
      for (int tick = 0; tick < 10; tick++) {
         state.tick(true, true, "stone");
      }
      int beforeRecovery = state.count();

      state.tick(true, false, "stone");

      assertTrue(state.accelerated());
      assertEquals(beforeRecovery - 3, state.count());
      assertEquals(1.0F - (float)(beforeRecovery - 3) / 20.0F, state.visibility(), 1.0E-6F);
   }

   @Test
   void targetSwitchRestoresNormalVisibilityRecoveryRate() {
      DisappearanceState state = new DisappearanceState(20);
      for (int tick = 0; tick < 10; tick++) {
         state.tick(true, true, "stone");
      }
      int beforeRecovery = state.count();

      state.tick(true, false, "dirt");

      assertFalse(state.accelerated());
      assertEquals(beforeRecovery - 1, state.count());
   }
}
