package io.github.fastformer.client.mode;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import org.junit.jupiter.api.Test;

class ClientModeViewTest {
   @Test void idleOwnershipMatchesThePreviousRulesForEveryWorkflowAndRoutedPhase() {
      var modes = ClientModes.registry();
      for (int flags = 0; flags < 512; flags++) {
         var observation = new ModeObservation(bit(flags, 0), bit(flags, 1), bit(flags, 2), bit(flags, 3),
            bit(flags, 4), bit(flags, 5), bit(flags, 6), bit(flags, 7), bit(flags, 8));
         for (State routed : State.values()) {
            for (boolean pending : new boolean[]{false, true}) {
               var view = ClientModeView.capture(modes, observation, routed, pending);
               assertEquals(modes.observe(observation), view.observedPhase());
               assertEquals(modes.hasInteractiveSession(observation), view.interactiveSession());
               assertEquals(routed == State.IDLE && !pending && modes.observe(observation) == State.IDLE,
                  view.idleActionsAllowed(), () -> observation + " / " + routed + " / " + pending);
            }
         }
      }
   }

   @Test void taskPriorityDoesNotEraseTheInteractiveSessionItCovers() {
      var view = ClientModeView.capture(ClientModes.registry(),
         new ModeObservation(true, true, false, false, false, false, true, false, false), State.RESTORING, false);
      assertEquals(State.RESTORING, view.observedPhase());
      assertTrue(view.interactiveSession());
      assertFalse(view.idleActionsAllowed());
   }

   @Test void viewsStayImmutableWhenTheNextEventObservesAnotherPhase() {
      var modes = ClientModes.registry();
      var before = ClientModeView.capture(modes, ModeObservation.idle(), State.IDLE, false);
      var during = ClientModeView.capture(modes, ModeObservation.idle(), State.SUBMITTING, false);
      var after = ClientModeView.capture(modes, ModeObservation.idle(), State.IDLE, false);
      assertTrue(before.idleActionsAllowed());
      assertFalse(during.idleActionsAllowed());
      assertEquals(before, after);
   }

   private static boolean bit(int value, int offset) { return (value & (1 << offset)) != 0; }
}
