package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import org.junit.jupiter.api.Test;

class QuickShapeButtonCaptureTest {
   @Test
   void vanillaReleaseHasNoQuickShapeOwner() {
      var input = new ClientInputSession();
      assertFalse(input.releaseQuickShapeButton(1));
      assertFalse(input.releaseQuickShapeButton(2));
   }

   @Test
   void capturedReleaseSurvivesRoutingChangeAndFinishesOnce() {
      var input = new ClientInputSession();
      input.routing.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(input.captureQuickShapeButton(1));
      assertFalse(input.captureQuickShapeButton(1));
      input.routing.observe(ClientInputStateMachine.State.IDLE);
      assertFalse(input.releaseQuickShapeButton(2));
      assertTrue(input.releaseQuickShapeButton(1));
      assertFalse(input.releaseQuickShapeButton(1));
      assertTrue(input.captureQuickShapeButton(1));
   }

   @Test
   void environmentResetDropsOldCapture() {
      var input = new ClientInputSession();
      input.captureQuickShapeButton(2);
      input.reset();
      assertFalse(input.releaseQuickShapeButton(2));
      assertTrue(input.captureQuickShapeButton(2));
   }
}
