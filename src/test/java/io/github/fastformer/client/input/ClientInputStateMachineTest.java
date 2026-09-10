package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientInputStateMachineTest {
   @Test
   void invalidAcknowledgementCannotLoseThePendingRequest() {
      var state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(state.submit(42));
      assertThrows(NullPointerException.class, () -> state.acknowledge(42, null));
      state.acknowledge(42, ClientInputStateMachine.State.BUILDING);
      assertEquals(ClientInputStateMachine.State.BUILDING, state.state());
      var transfer = java.util.UUID.randomUUID();
      state.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(state.submit(transfer));
      assertThrows(NullPointerException.class, () -> state.acknowledge(transfer, null));
      state.acknowledge(transfer, ClientInputStateMachine.State.ADJUSTING);
      assertEquals(ClientInputStateMachine.State.ADJUSTING, state.state());
   }

   @Test
   void workspaceSubmissionRequiresItsOwnResultAndAllowsCancellation() {
      var state = new ClientInputStateMachine();
      var transfer = java.util.UUID.randomUUID();
      state.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(state.submit(transfer));
      state.acknowledge(0L, ClientInputStateMachine.State.IDLE);
      state.acknowledge(java.util.UUID.randomUUID(), ClientInputStateMachine.State.IDLE);
      state.observe(ClientInputStateMachine.State.ADJUSTING);
      assertEquals(ClientInputStateMachine.State.SUBMITTING, state.state());
      assertFalse(state.submit(java.util.UUID.randomUUID()));
      state.acknowledge(transfer, ClientInputStateMachine.State.ADJUSTING);
      assertEquals(ClientInputStateMachine.State.ADJUSTING, state.state());
      assertTrue(state.submit(transfer));
      assertTrue(state.cancel());
      state.acknowledge(transfer, ClientInputStateMachine.State.IDLE);
      assertEquals(ClientInputStateMachine.State.CANCELLING, state.state());
      state.reset();
      state.acknowledge(transfer, ClientInputStateMachine.State.ADJUSTING);
      assertEquals(ClientInputStateMachine.State.IDLE, state.state());
   }

   @Test
   void submissionStartsOnlyFromAConfirmedRequestOwningPhase() {
      ClientInputStateMachine state = new ClientInputStateMachine();

      assertFalse(state.submit(1L));
      assertFalse(state.submit(java.util.UUID.randomUUID()));
      state.observe(ClientInputStateMachine.State.GEOMETRY);
      assertTrue(state.submit(2L));
      state.acknowledge(2L, ClientInputStateMachine.State.GEOMETRY);
      state.observe(ClientInputStateMachine.State.SELECTING);
      assertFalse(state.submit(3L));

      state.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(state.submit(4L));
      state.acknowledge(4L, ClientInputStateMachine.State.ADJUSTING);
      assertTrue(state.submit(java.util.UUID.randomUUID()));
   }

   @Test
   void submissionBlocksLateEventsUntilMatchingAcknowledgement() {
      var state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.BUILDING);
      long gesture = state.beginGesture();
      assertTrue(state.submit(12));
      assertFalse(state.accepts(gesture));
      state.observe(ClientInputStateMachine.State.BUILDING);
      state.observe(ClientInputStateMachine.State.IDLE);
      assertEquals(ClientInputStateMachine.State.SUBMITTING, state.state());
      assertFalse(state.submit(13));
      state.acknowledge(11, ClientInputStateMachine.State.IDLE);
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.INTERACTION));
      state.acknowledge(12, ClientInputStateMachine.State.BUILDING);
      assertEquals(ClientInputStateMachine.State.BUILDING, state.state());
      assertTrue(state.submit(13));
      state.acknowledge(13, ClientInputStateMachine.State.PLACING);
      assertEquals(ClientInputStateMachine.State.PLACING, state.state());
   }

   @Test
   void acknowledgementCannotUndoCancellationOrDisconnect() {
      var state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(state.submit(1));
      assertTrue(state.cancel());
      state.acknowledge(1, ClientInputStateMachine.State.PLACING);
      assertEquals(ClientInputStateMachine.State.CANCELLING, state.state());
      state.reset();
      state.acknowledge(1, ClientInputStateMachine.State.PLACING);
      assertEquals(ClientInputStateMachine.State.IDLE, state.state());
   }

   @Test
   void onlyTheMatchingRequestCanAbortSubmission() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(state.submit(41L));

      state.abortSubmission(40L, ClientInputStateMachine.State.BUILDING);
      assertEquals(ClientInputStateMachine.State.SUBMITTING, state.state());

      state.abortSubmission(41L, ClientInputStateMachine.State.BUILDING);
      assertEquals(ClientInputStateMachine.State.BUILDING, state.state());
   }

   @Test
   void cancellationSurvivesStalePreviewAndRecoveryUntilIdle() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.SELECTING);
      long token = state.beginGesture();
      assertTrue(state.cancel());
      for (var observed : new ClientInputStateMachine.State[] {
         ClientInputStateMachine.State.SELECTING,
         ClientInputStateMachine.State.PLACING,
         ClientInputStateMachine.State.RESTORING
      }) {
         state.observe(observed);
         assertEquals(ClientInputStateMachine.State.CANCELLING, state.state());
         assertFalse(state.cancel());
         assertEquals(0L, state.beginGesture());
      }
      state.observe(ClientInputStateMachine.State.IDLE);
      assertEquals(ClientInputStateMachine.Dispatch.VANILLA,
         state.dispatch(ClientInputStateMachine.InputKind.POINTER));
      assertFalse(state.accepts(token));
      assertTrue(state.accepts(state.beginGesture()));
   }

   @Test
   void switchingBuildingToGeometryInvalidatesGesture() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.BUILDING);
      long token = state.beginGesture();
      state.observe(ClientInputStateMachine.State.GEOMETRY);
      assertFalse(state.accepts(token));
      assertTrue(state.cancel());
   }

   @Test
   void recoveryCannotBePausedByQ() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.RESTORING);
      assertFalse(state.cancel());
      for (var input : ClientInputStateMachine.InputKind.values()) {
         assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, state.dispatch(input));
      }
      state.observe(ClientInputStateMachine.State.IDLE);
      assertFalse(state.cancel());
   }

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
   void vanillaExtensionsCannotRunInsideAFastFormerSession() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      assertTrue(state.routesToVanilla(ClientInputStateMachine.InputKind.INTERACTION));

      for (ClientInputStateMachine.State sessionState : new ClientInputStateMachine.State[] {
         ClientInputStateMachine.State.BUILDING,
         ClientInputStateMachine.State.GEOMETRY,
         ClientInputStateMachine.State.SELECTING,
         ClientInputStateMachine.State.ADJUSTING,
         ClientInputStateMachine.State.PLACING,
         ClientInputStateMachine.State.RESTORING
      }) {
         state.observe(sessionState);
         assertFalse(state.routesToVanilla(ClientInputStateMachine.InputKind.INTERACTION));
      }
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
