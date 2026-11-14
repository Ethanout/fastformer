package io.github.fastformer.client.mode;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.input.KeyDecision;
import io.github.fastformer.client.mode.input.KeyInput;
import io.github.fastformer.client.mode.input.KeyView;
import org.junit.jupiter.api.Test;

class ClientModeKeyTest {
   @Test void dedicatedModeKeysPreserveTheExistingPriorityForEveryContext() {
      for (State state : State.values()) for (int flags = 0; flags < 128; flags++) {
         var view = new KeyView(bit(flags, 0), bit(flags, 1), bit(flags, 2),
            bit(flags, 3), bit(flags, 4), bit(flags, 5));
         for (int key : new int[] {65, 70, 81, 82, 89, 90, 257, 335}) for (int action = 0; action <= 2; action++) {
            var input = new KeyInput(key, action, bit(flags, 6));
            assertEquals(previousDecision(state, input, view), ClientModes.registry().onKey(state, input, view),
               state + " flags=" + flags + " key=" + key + " action=" + action);
         }
      }
   }

   @Test void fillOnlyConfirmsOnPressWhenReadyAndPassesCancellation() {
      var ready = new KeyView(true, true, false, false, true, false);
      assertEquals(KeyDecision.CONFIRM_FILL, decide(State.FILL_PREVIEW, 257, 1, ready));
      assertEquals(KeyDecision.CONFIRM_FILL, decide(State.FILL_PREVIEW, 335, 1, ready));
      assertEquals(KeyDecision.CONSUME, decide(State.FILL_PREVIEW, 257, 0, ready));
      assertEquals(KeyDecision.PASS, decide(State.FILL_PREVIEW, 81, 1, ready));
      var waiting = new KeyView(true, true, false, true, false, false);
      assertEquals(KeyDecision.CONSUME, decide(State.FILL_PREVIEW, 70, 1, waiting));
      assertEquals(KeyDecision.START_FILL, decide(State.IDLE, 70, 1, new KeyView(true, true, true, true, false, true)));
   }

   @Test void activeWorkflowsCannotInheritIdleToolEntryFromFallback() {
      var view = new KeyView(true, true, false, true, false, true);
      for (State state : new State[] {State.BUILDING, State.GEOMETRY, State.SELECTING, State.ADJUSTING, State.PLACING}) {
         assertEquals(KeyDecision.PASS, decide(state, 70, 1, view));
         assertEquals(KeyDecision.PASS, decide(state, 82, 1, view));
      }
   }

   private static KeyDecision decide(State state, int key, int action, KeyView view) {
      return ClientModes.registry().onKey(state, new KeyInput(key, action, false), view);
   }

   private static KeyDecision previousDecision(State state, KeyInput input, KeyView view) {
      if (state == State.QUICK_REPLACE) {
         if (view.screenClosed() && input.press()) {
            if (input.key() == 82 && !input.controlDown()) return KeyDecision.TOGGLE_REPLACEMENT;
            if (input.controlDown() && input.key() == 90) return KeyDecision.REPLACEMENT_UNDO;
            if (input.controlDown() && input.key() == 89) return KeyDecision.REPLACEMENT_REDO;
         }
         return KeyDecision.CONSUME;
      }
      if (view.playerReady() && state == State.FILL_PREVIEW && (view.fillKey() || input.enter()))
         return input.press() && view.fillReady() ? KeyDecision.CONFIRM_FILL : KeyDecision.CONSUME;
      if (view.playerReady() && view.idleActionsAllowed()) {
         if (view.fillKey() && view.canStartFill()) return input.press() ? KeyDecision.START_FILL : KeyDecision.CONSUME;
         if (input.press() && input.key() == 82 && !input.controlDown()) return KeyDecision.TOGGLE_REPLACEMENT;
      }
      return KeyDecision.PASS;
   }

   private static boolean bit(int flags, int offset) { return (flags & (1 << offset)) != 0; }
}
