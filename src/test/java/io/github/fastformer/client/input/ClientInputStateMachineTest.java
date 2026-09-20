package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientInputStateMachineTest {
   @Test
   void everyStateAndInputKindMatchesTheDispatchPolicy() {
      ClientInputStateMachine.Dispatch B = ClientInputStateMachine.Dispatch.BLOCKED;
      ClientInputStateMachine.Dispatch C = ClientInputStateMachine.Dispatch.CANCEL;
      ClientInputStateMachine.Dispatch V = ClientInputStateMachine.Dispatch.VANILLA;
      ClientInputStateMachine.Dispatch F = ClientInputStateMachine.Dispatch.BUILDING;
      ClientInputStateMachine.Dispatch G = ClientInputStateMachine.Dispatch.GEOMETRY;
      ClientInputStateMachine.Dispatch O = ClientInputStateMachine.Dispatch.OPERATION;
      ClientInputStateMachine.Dispatch[][] expected = {
         {V, B, V, V, V, B, V, B},
         {F, F, F, F, F, B, B, C},
         {G, G, G, G, G, B, B, C},
         {O, B, O, O, O, O, O, C},
         {O, O, O, O, O, O, O, C},
         {B, B, B, B, B, B, B, C},
         {B, B, B, B, B, B, B, C},
         {B, B, B, B, B, B, B, B},
         {B, B, B, B, B, B, B, B}
      };

      ClientInputStateMachine.State[] states = ClientInputStateMachine.State.values();
      ClientInputStateMachine.InputKind[] inputs = ClientInputStateMachine.InputKind.values();
      assertEquals(states.length, expected.length);
      for (int stateIndex = 0; stateIndex < states.length; stateIndex++) {
         assertEquals(inputs.length, expected[stateIndex].length);
         ClientInputStateMachine machine = machineIn(states[stateIndex]);
         for (int inputIndex = 0; inputIndex < inputs.length; inputIndex++) {
            assertEquals(
               expected[stateIndex][inputIndex],
               machine.dispatch(inputs[inputIndex]),
               states[stateIndex] + " x " + inputs[inputIndex]
            );
         }
      }
   }

   @Test
   void blockedEventsDoNotChangeStateGenerationOrGesture() {
      for (ClientInputStateMachine.State state : ClientInputStateMachine.State.values()) {
         ClientInputStateMachine machine = machineIn(state);
         long gesture = machine.beginGesture(1);
         long generation = machine.generation();
         ClientInputStateMachine.State before = machine.state();
         for (ClientInputStateMachine.InputKind input : ClientInputStateMachine.InputKind.values()) {
            if (machine.dispatch(input) != ClientInputStateMachine.Dispatch.BLOCKED) {
               continue;
            }
            assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, machine.dispatch(input));
            assertEquals(before, machine.state(), state + " x " + input);
            assertEquals(generation, machine.generation(), state + " x " + input);
            assertEquals(gesture != 0L, machine.accepts(gesture), state + " x " + input);
         }
      }
   }

   @Test
   void blockedEventsDoNotReleaseThePendingRequestOwner() {
      ClientInputStateMachine placement = new ClientInputStateMachine();
      placement.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(placement.submit(42L));
      assertBlockedInputsArePure(placement);
      assertTrue(placement.completeSubmission(
         42L, ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.BUILDING
      ));
      assertEquals(ClientInputStateMachine.State.BUILDING, placement.state());

      ClientInputStateMachine workspace = new ClientInputStateMachine();
      java.util.UUID transferId = java.util.UUID.randomUUID();
      workspace.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(workspace.submit(transferId));
      assertBlockedInputsArePure(workspace);
      assertTrue(workspace.completeSubmission(
         transferId, ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.ADJUSTING
      ));
      assertEquals(ClientInputStateMachine.State.ADJUSTING, workspace.state());
   }

   @Test
   void selectionCreationAndPasteRespectTheOwningSession() {
      var state = new ClientInputStateMachine();
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, state.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION));
      assertEquals(ClientInputStateMachine.Dispatch.VANILLA, state.dispatch(ClientInputStateMachine.InputKind.PASTE_WORKSPACE));
      for (var phase : new ClientInputStateMachine.State[] {
         ClientInputStateMachine.State.BUILDING, ClientInputStateMachine.State.GEOMETRY,
         ClientInputStateMachine.State.PLACING, ClientInputStateMachine.State.RESTORING
      }) {
         state.observe(phase);
         assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, state.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION));
         assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, state.dispatch(ClientInputStateMachine.InputKind.PASTE_WORKSPACE));
      }
      for (var phase : new ClientInputStateMachine.State[] {
         ClientInputStateMachine.State.SELECTING, ClientInputStateMachine.State.ADJUSTING
      }) {
         state.observe(phase);
         assertEquals(ClientInputStateMachine.Dispatch.OPERATION, state.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION));
         assertEquals(ClientInputStateMachine.Dispatch.OPERATION, state.dispatch(ClientInputStateMachine.InputKind.PASTE_WORKSPACE));
      }
      state.submit(1);
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, state.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION));
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, state.dispatch(ClientInputStateMachine.InputKind.PASTE_WORKSPACE));
      state.cancel();
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, state.dispatch(ClientInputStateMachine.InputKind.PASTE_WORKSPACE));
   }

   @Test
   void submitEventIsAcceptedOnlyByInteractiveSessionPhases() {
      var state = new ClientInputStateMachine();
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.SUBMIT));
      for (var phase : new ClientInputStateMachine.State[] {
         ClientInputStateMachine.State.BUILDING,
         ClientInputStateMachine.State.GEOMETRY,
         ClientInputStateMachine.State.ADJUSTING
      }) {
         state.observe(phase);
         assertNotEquals(ClientInputStateMachine.Dispatch.BLOCKED,
            state.dispatch(ClientInputStateMachine.InputKind.SUBMIT));
      }
      state.observe(ClientInputStateMachine.State.SELECTING);
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.SUBMIT));
      for (var phase : new ClientInputStateMachine.State[] {
         ClientInputStateMachine.State.IDLE,
         ClientInputStateMachine.State.PLACING,
         ClientInputStateMachine.State.SUBMITTING,
         ClientInputStateMachine.State.CANCELLING,
         ClientInputStateMachine.State.RESTORING
      }) {
         state.reset();
         if (phase != ClientInputStateMachine.State.IDLE) {
            if (phase == ClientInputStateMachine.State.SUBMITTING) {
               state.observe(ClientInputStateMachine.State.BUILDING);
               state.submit(1L);
            } else if (phase == ClientInputStateMachine.State.CANCELLING) {
               state.observe(ClientInputStateMachine.State.BUILDING);
               state.cancel();
            } else {
               state.observe(phase);
            }
         }
         assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
            state.dispatch(ClientInputStateMachine.InputKind.SUBMIT));
      }
   }

   @Test
   void releaseFinishesOnlyItsOwningButtonAndCannotRepeat() {
      var state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.SELECTING);
      long left = state.beginGesture(0);
      long right = state.beginGesture(1);
      assertFalse(state.finishGesture(0, left));
      assertFalse(state.finishGesture(0, right));
      assertTrue(state.accepts(right));
      assertTrue(state.finishGesture(1, right));
      assertFalse(state.finishGesture(1, right));
      assertFalse(state.accepts(right));
      long current = state.beginGesture(1);
      state.cancel();
      assertFalse(state.finishGesture(1, current));
   }

   @Test
   void invalidAcknowledgementCannotLoseThePendingRequest() {
      var state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(state.submit(42));
      assertThrows(NullPointerException.class, () -> state.completeSubmission(
         42, ClientInputStateMachine.SubmissionEvent.SUCCEEDED, null
      ));
      state.completeSubmission(42, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.BUILDING);
      assertEquals(ClientInputStateMachine.State.BUILDING, state.state());
      var transfer = java.util.UUID.randomUUID();
      state.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(state.submit(transfer));
      assertThrows(NullPointerException.class, () -> state.completeSubmission(
         transfer, ClientInputStateMachine.SubmissionEvent.SUCCEEDED, null
      ));
      state.completeSubmission(transfer, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.ADJUSTING);
      assertEquals(ClientInputStateMachine.State.ADJUSTING, state.state());
   }

   @Test
   void workspaceSubmissionRequiresItsOwnResultAndAllowsCancellation() {
      var state = new ClientInputStateMachine();
      var transfer = java.util.UUID.randomUUID();
      state.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(state.submit(transfer));
      assertFalse(state.completeSubmission(0L, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.IDLE));
      assertFalse(state.completeSubmission(java.util.UUID.randomUUID(), ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.IDLE));
      state.observe(ClientInputStateMachine.State.ADJUSTING);
      assertEquals(ClientInputStateMachine.State.SUBMITTING, state.state());
      assertFalse(state.submit(java.util.UUID.randomUUID()));
      assertTrue(state.completeSubmission(transfer, ClientInputStateMachine.SubmissionEvent.FAILED,
         ClientInputStateMachine.State.ADJUSTING));
      assertEquals(ClientInputStateMachine.State.ADJUSTING, state.state());
      assertTrue(state.submit(transfer));
      assertTrue(state.cancel());
      assertFalse(state.completeSubmission(transfer, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.IDLE));
      assertEquals(ClientInputStateMachine.State.CANCELLING, state.state());
      state.reset();
      assertFalse(state.completeSubmission(transfer, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.ADJUSTING));
      assertEquals(ClientInputStateMachine.State.IDLE, state.state());
   }

   @Test
   void submissionStartsOnlyFromAConfirmedRequestOwningPhase() {
      ClientInputStateMachine state = new ClientInputStateMachine();

      assertFalse(state.submit(1L));
      assertFalse(state.submit(java.util.UUID.randomUUID()));
      state.observe(ClientInputStateMachine.State.GEOMETRY);
      assertTrue(state.submit(2L));
      assertTrue(state.completeSubmission(2L, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.GEOMETRY));
      state.observe(ClientInputStateMachine.State.SELECTING);
      assertFalse(state.submit(3L));

      state.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(state.submit(4L));
      assertTrue(state.completeSubmission(4L, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.ADJUSTING));
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
      assertFalse(state.completeSubmission(11, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.IDLE));
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED,
         state.dispatch(ClientInputStateMachine.InputKind.INTERACTION));
      assertTrue(state.completeSubmission(12, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.BUILDING));
      assertEquals(ClientInputStateMachine.State.BUILDING, state.state());
      assertTrue(state.submit(13));
      assertTrue(state.completeSubmission(13, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.PLACING));
      assertEquals(ClientInputStateMachine.State.PLACING, state.state());
   }

   @Test
   void acknowledgementCannotUndoCancellationOrDisconnect() {
      var state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(state.submit(1));
      assertTrue(state.cancel());
      assertFalse(state.completeSubmission(1, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.PLACING));
      assertEquals(ClientInputStateMachine.State.CANCELLING, state.state());
      state.reset();
      assertFalse(state.completeSubmission(1, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.PLACING));
      assertEquals(ClientInputStateMachine.State.IDLE, state.state());
   }

   @Test
   void onlyTheMatchingRequestCanAbortSubmission() {
      ClientInputStateMachine state = new ClientInputStateMachine();
      state.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(state.submit(41L));

      assertFalse(state.completeSubmission(40L, ClientInputStateMachine.SubmissionEvent.EXPIRED,
         ClientInputStateMachine.State.BUILDING));
      assertEquals(ClientInputStateMachine.State.SUBMITTING, state.state());

      assertTrue(state.completeSubmission(41L, ClientInputStateMachine.SubmissionEvent.EXPIRED,
         ClientInputStateMachine.State.BUILDING));
      assertEquals(ClientInputStateMachine.State.BUILDING, state.state());
   }

   @Test
   void failedSubmissionReturnsToItsOwningPhaseDespiteAStaleSnapshot() {
      ClientInputStateMachine placement = new ClientInputStateMachine();
      placement.observe(ClientInputStateMachine.State.GEOMETRY);
      assertTrue(placement.submit(41L));

      assertTrue(placement.completeSubmission(
         41L,
         ClientInputStateMachine.SubmissionEvent.FAILED,
         ClientInputStateMachine.State.IDLE
      ));
      assertEquals(ClientInputStateMachine.State.GEOMETRY, placement.state());

      ClientInputStateMachine workspace = new ClientInputStateMachine();
      java.util.UUID transfer = java.util.UUID.randomUUID();
      workspace.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(workspace.submit(transfer));

      assertTrue(workspace.completeSubmission(
         transfer,
         ClientInputStateMachine.SubmissionEvent.EXPIRED,
         ClientInputStateMachine.State.SUBMITTING
      ));
      assertEquals(ClientInputStateMachine.State.ADJUSTING, workspace.state());
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

   private static ClientInputStateMachine machineIn(ClientInputStateMachine.State state) {
      ClientInputStateMachine machine = new ClientInputStateMachine();
      switch (state) {
         case IDLE -> { }
         case SUBMITTING -> {
            machine.observe(ClientInputStateMachine.State.BUILDING);
            assertTrue(machine.submit(1L));
         }
         case CANCELLING -> {
            machine.observe(ClientInputStateMachine.State.BUILDING);
            assertTrue(machine.cancel());
         }
         default -> machine.observe(state);
      }
      assertEquals(state, machine.state());
      return machine;
   }

   private static void assertBlockedInputsArePure(ClientInputStateMachine machine) {
      long generation = machine.generation();
      for (ClientInputStateMachine.InputKind input : ClientInputStateMachine.InputKind.values()) {
         if (input == ClientInputStateMachine.InputKind.CANCEL) {
            continue;
         }
         assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, machine.dispatch(input));
         assertEquals(ClientInputStateMachine.State.SUBMITTING, machine.state());
         assertEquals(generation, machine.generation());
      }
   }
}
