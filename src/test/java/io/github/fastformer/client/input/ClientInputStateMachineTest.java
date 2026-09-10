package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientInputStateMachineTest {
   @Test
   void staleGestureIsRejectedAfterPlacementStarts() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.SELECTING);
      long token = state.beginGesture();
      assertTrue(state.accepts(token));

      state.observe(ClientInputStateMachine.State.PLACING);
      assertFalse(state.accepts(token));
   }

   @Test
   void aNewGestureInvalidatesThePreviousToken() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.ADJUSTING);
      long oldToken = state.beginGesture();
      long newToken = state.beginGesture();

      assertFalse(state.accepts(oldToken));
      assertTrue(state.accepts(newToken));
   }

   @Test
   void resetReturnsToIdleAndInvalidatesGesture() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.SELECTING);
      long token = state.beginGesture();
      state.reset();

      assertEquals(ClientInputStateMachine.State.IDLE, state.state());
      assertFalse(state.accepts(token));
   }

   @Test
   void changingInputPhaseInvalidatesGestureEvenWhenBothPhasesAcceptInput() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.SELECTING);
      long token = state.beginGesture();

      state.observe(ClientInputStateMachine.State.ADJUSTING);

      assertFalse(state.accepts(token));
   }

   @Test
   void cancellingPhaseRejectsAllInputUntilServerClearsTheSession() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.SELECTING);
      long token = state.beginGesture();

      assertTrue(state.cancel());

      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.POINTER));
      assertFalse(state.accepts(token));
   }

   @Test
   void taskPhasesRouteOnlyCancellation() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.PLACING);

      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.POINTER));
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.INTERACTION));
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.SCROLL));
      assertEquals(ClientInputStateMachine.Dispatch.CANCEL,
         state.dispatch(ClientInputStateMachine.InputKind.CANCEL));
   }

   @Test
   void cancellingPhaseRejectsRepeatedCancellation() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.SELECTING);
      assertTrue(state.cancel());

      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.CANCEL));
      assertFalse(state.cancel());
   }

   @Test
   void placingInvalidatesOldGesture() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.SELECTING);
      long token = state.beginGesture();

      state.observe(ClientInputStateMachine.State.PLACING);

      assertEquals(ClientInputStateMachine.State.PLACING, state.state());
      assertFalse(state.accepts(token));
   }

   @Test
   void taskRouteIsTheOnlyOwnerOfPointerInputDuringPlacement() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.PLACING);

      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.POINTER));
      assertEquals(ClientInputStateMachine.Dispatch.CANCEL,
         state.dispatch(ClientInputStateMachine.InputKind.CANCEL));
   }

   @Test
   void sessionRoutesAreMutuallyExclusive() {
      ClientInputStateMachine state = new ClientInputStateMachine();

      state.observe(ClientInputStateMachine.State.BUILDING);
      assertEquals(ClientInputStateMachine.Dispatch.BUILDING,
         state.dispatch(ClientInputStateMachine.InputKind.INTERACTION));

      state.observe(ClientInputStateMachine.State.GEOMETRY);
      assertEquals(ClientInputStateMachine.Dispatch.GEOMETRY,
         state.dispatch(ClientInputStateMachine.InputKind.INTERACTION));

      state.observe(ClientInputStateMachine.State.SELECTING);
      assertEquals(ClientInputStateMachine.Dispatch.OPERATION,
         state.dispatch(ClientInputStateMachine.InputKind.POINTER));
   }

   @Test
   void cancellingRouteRejectsRepeatedAndLateInput() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.SELECTING);
      assertTrue(state.cancel());

      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.POINTER));
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.CANCEL));
   }
}
