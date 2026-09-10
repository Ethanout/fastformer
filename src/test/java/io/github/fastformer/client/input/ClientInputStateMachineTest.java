package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientInputStateMachineTest {
   @Test
   void staleGestureIsRejectedAfterPlacementStarts() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.transition(ClientInputStateMachine.Phase.SELECTING);
      long token = state.beginGesture();
      assertTrue(state.accepts(token));

      state.transition(ClientInputStateMachine.Phase.PLACING);
      assertFalse(state.accepts(token));
   }

   @Test
   void aNewGestureInvalidatesThePreviousToken() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.transition(ClientInputStateMachine.Phase.ADJUSTING);
      long oldToken = state.beginGesture();
      long newToken = state.beginGesture();

      assertFalse(state.accepts(oldToken));
      assertTrue(state.accepts(newToken));
   }

   @Test
   void resetReturnsToIdleAndInvalidatesGesture() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.transition(ClientInputStateMachine.Phase.CONFIRMING);
      long token = state.beginGesture();
      state.reset();

      assertEquals(ClientInputStateMachine.Phase.IDLE, state.phase());
      assertFalse(state.accepts(token));
   }

   @Test
   void changingInputPhaseInvalidatesGestureEvenWhenBothPhasesAcceptInput() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.transition(ClientInputStateMachine.Phase.SELECTING);
      long token = state.beginGesture();

      state.transition(ClientInputStateMachine.Phase.ADJUSTING);

      assertFalse(state.accepts(token));
   }

   @Test
   void cancellingPhaseRejectsAllInputUntilServerClearsTheSession() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.transition(ClientInputStateMachine.Phase.SELECTING);
      long token = state.beginGesture();

      state.transition(ClientInputStateMachine.Phase.CANCELLING);

      assertFalse(state.phase().acceptsInput());
      assertFalse(state.accepts(token));
   }

   @Test
   void taskPhasesRouteOnlyCancellation() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.transition(ClientInputStateMachine.Phase.PLACING);

      assertFalse(state.accepts(ClientInputStateMachine.InputKind.POINTER));
      assertFalse(state.accepts(ClientInputStateMachine.InputKind.INTERACTION));
      assertFalse(state.accepts(ClientInputStateMachine.InputKind.SCROLL));
      assertTrue(state.accepts(ClientInputStateMachine.InputKind.CANCEL));
   }

   @Test
   void cancellingPhaseRejectsRepeatedCancellation() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.transition(ClientInputStateMachine.Phase.CANCELLING);

      assertFalse(state.accepts(ClientInputStateMachine.InputKind.CANCEL));
   }

   @Test
   void synchronizingAChangedPhaseInvalidatesOldGesture() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.transition(ClientInputStateMachine.Phase.SELECTING);
      long token = state.beginGesture();

      state.synchronize(false, false, true, false, false);

      assertEquals(ClientInputStateMachine.Phase.PLACING, state.phase());
      assertFalse(state.accepts(token));
   }

   @Test
   void taskRouteIsTheOnlyOwnerOfPointerInputDuringPlacement() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.synchronize(false, false, true, false, false, false);

      assertEquals(ClientInputStateMachine.Route.TASK, state.route());
      assertEquals(ClientInputStateMachine.Dispatch.TASK,
         state.dispatch(ClientInputStateMachine.InputKind.POINTER));
      assertEquals(ClientInputStateMachine.Dispatch.CANCEL,
         state.dispatch(ClientInputStateMachine.InputKind.CANCEL));
   }

   @Test
   void sessionRoutesAreMutuallyExclusive() {
      ClientInputStateMachine state = new ClientInputStateMachine();

      state.synchronize(false, false, false, false, true, true);
      assertEquals(ClientInputStateMachine.Dispatch.BUILDING,
         state.dispatch(ClientInputStateMachine.InputKind.INTERACTION));

      state.synchronize(false, false, false, false, true, false);
      assertEquals(ClientInputStateMachine.Dispatch.GEOMETRY,
         state.dispatch(ClientInputStateMachine.InputKind.INTERACTION));

      state.synchronize(false, false, false, true, true, true);
      assertEquals(ClientInputStateMachine.Dispatch.OPERATION,
         state.dispatch(ClientInputStateMachine.InputKind.POINTER));
   }

   @Test
   void cancellingRouteRejectsRepeatedAndLateInput() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.synchronize(true, false, false, false, false, false);

      assertEquals(ClientInputStateMachine.Route.TASK, state.route());
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.POINTER));
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.CANCEL));
   }
}
