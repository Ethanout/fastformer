package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceKeyboardSemanticsTest {
   private static final ClientInputStateMachine.Dispatch BLOCKED = ClientInputStateMachine.Dispatch.BLOCKED;
   private static final ClientInputStateMachine.Dispatch CANCEL = ClientInputStateMachine.Dispatch.CANCEL;
   private static final ClientInputStateMachine.Dispatch VANILLA = ClientInputStateMachine.Dispatch.VANILLA;
   private static final ClientInputStateMachine.Dispatch BUILDING = ClientInputStateMachine.Dispatch.BUILDING;
   private static final ClientInputStateMachine.Dispatch GEOMETRY = ClientInputStateMachine.Dispatch.GEOMETRY;
   private static final ClientInputStateMachine.Dispatch OPERATION = ClientInputStateMachine.Dispatch.OPERATION;

   /** Facts of an empty idle workspace that holds no local undo step. */
   private static WorkspaceKeyboardSemantics.WorkspaceFacts empty() {
      return WorkspaceKeyboardSemantics.WorkspaceFacts.inactive();
   }

   /** Facts of a workspace that shows parts and holds one local undo step. */
   private static WorkspaceKeyboardSemantics.WorkspaceFacts withParts() {
      return new WorkspaceKeyboardSemantics.WorkspaceFacts(true, 1, false, false);
   }

   private static WorkspaceKeyboardSemantics.Decision decideUndo(
      ClientInputStateMachine.Dispatch keyRoute,
      boolean workspaceActive,
      int undoDepth,
      boolean editing,
      boolean submissionPending
   ) {
      return WorkspaceKeyboardSemantics.decide(
         WorkspaceKeyboardSemantics.Command.UNDO,
         keyRoute,
         keyRoute,
         new WorkspaceKeyboardSemantics.WorkspaceFacts(
            workspaceActive, undoDepth, editing, submissionPending
         )
      );
   }

   private static WorkspaceKeyboardSemantics.Decision decideOn(WorkspaceKeyboardSemantics.Command command) {
      return WorkspaceKeyboardSemantics.decide(command, OPERATION, OPERATION, withParts());
   }

   @Test
   void physicalKeyPressesProduceWorkspaceCommands() {
      assertEquals(WorkspaceKeyboardSemantics.Command.COPY,
         WorkspaceKeyboardSemantics.fromPhysicalKey(1, 67, true));
      assertEquals(WorkspaceKeyboardSemantics.Command.PASTE,
         WorkspaceKeyboardSemantics.fromPhysicalKey(1, 86, true));
      assertEquals(WorkspaceKeyboardSemantics.Command.SELECT_ALL,
         WorkspaceKeyboardSemantics.fromPhysicalKey(1, 65, true));
      assertEquals(WorkspaceKeyboardSemantics.Command.UNDO,
         WorkspaceKeyboardSemantics.fromPhysicalKey(1, 90, true));
      assertEquals(WorkspaceKeyboardSemantics.Command.REMOVE_SELECTED,
         WorkspaceKeyboardSemantics.fromPhysicalKey(1, 261, true));
      assertEquals(WorkspaceKeyboardSemantics.Command.MARK_SELECTED_FOR_DELETION,
         WorkspaceKeyboardSemantics.fromPhysicalKey(1, 259, false));
      assertEquals(WorkspaceKeyboardSemantics.Command.NONE,
         WorkspaceKeyboardSemantics.fromPhysicalKey(0, 67, true));
      assertEquals(WorkspaceKeyboardSemantics.Command.NONE,
         WorkspaceKeyboardSemantics.fromPhysicalKey(1, 67, false));
   }

   @Test
   void workspaceEditsRunOnlyInAnOperationPhase() {
      WorkspaceKeyboardSemantics.Decision otherPhase = WorkspaceKeyboardSemantics.decide(
         WorkspaceKeyboardSemantics.Command.COPY, VANILLA, VANILLA, withParts()
      );
      assertEquals(WorkspaceKeyboardSemantics.Rejection.PHASE_BLOCKED, otherPhase.rejection());
      assertFalse(otherPhase.consumesPress());
      assertFalse(otherPhase.handsPressToWorldUndo());

      WorkspaceKeyboardSemantics.Decision noContent = WorkspaceKeyboardSemantics.decide(
         WorkspaceKeyboardSemantics.Command.SELECT_ALL, OPERATION, OPERATION, empty()
      );
      assertEquals(WorkspaceKeyboardSemantics.Rejection.WORKSPACE_INACTIVE, noContent.rejection());
      assertFalse(noContent.consumesPress());
      assertFalse(noContent.handsPressToWorldUndo());
   }

   @Test
   void acceptedCommandsNameTheirCommand() {
      assertEquals(WorkspaceKeyboardSemantics.Command.COPY, decideOn(WorkspaceKeyboardSemantics.Command.COPY).command());
      assertTrue(decideOn(WorkspaceKeyboardSemantics.Command.REMOVE_SELECTED).accepted());
      assertTrue(decideOn(WorkspaceKeyboardSemantics.Command.MARK_SELECTED_FOR_DELETION).accepted());
   }

   @Test
   void pasteUsesItsDedicatedPhaseRoute() {
      assertTrue(WorkspaceKeyboardSemantics.decide(
         WorkspaceKeyboardSemantics.Command.PASTE, VANILLA, OPERATION, empty()
      ).accepted());

      WorkspaceKeyboardSemantics.Decision blocked = WorkspaceKeyboardSemantics.decide(
         WorkspaceKeyboardSemantics.Command.PASTE, OPERATION, BLOCKED, withParts()
      );
      assertEquals(WorkspaceKeyboardSemantics.Rejection.PHASE_BLOCKED, blocked.rejection());
      assertFalse(blocked.handsPressToWorldUndo());
   }

   @Test
   void unknownKeysAreIgnored() {
      WorkspaceKeyboardSemantics.Decision decision = decideOn(WorkspaceKeyboardSemantics.Command.NONE);

      assertFalse(decision.accepted());
      assertEquals(WorkspaceKeyboardSemantics.Rejection.NONE, decision.rejection());
      assertFalse(decision.consumesPress());
      assertFalse(decision.handsPressToWorldUndo());
   }

   /** BUG-AI: an empty workspace can still own the undo step. */
   @Test
   void undoUsesLocalHistoryEvenWhenTheWorkspaceShowsNoParts() {
      WorkspaceKeyboardSemantics.Decision decision = decideUndo(OPERATION, false, 1, false, false);

      assertTrue(decision.accepted());
      assertEquals(WorkspaceKeyboardSemantics.Command.UNDO, decision.command());
      assertTrue(decision.consumesPress());
   }

   /** BUG-AC: no local step leaves the press to the world undo request. */
   @Test
   void undoWithoutLocalHistoryLeavesThePressToTheWorldRequest() {
      WorkspaceKeyboardSemantics.Decision decision = decideUndo(OPERATION, true, 0, false, false);

      assertEquals(WorkspaceKeyboardSemantics.Rejection.NO_LOCAL_HISTORY, decision.rejection());
      assertTrue(decision.handsPressToWorldUndo());
      assertFalse(decision.consumesPress());
   }

   @Test
   void undoOutsideAnOperationSessionLeavesThePressToTheWorldRequest() {
      for (ClientInputStateMachine.Dispatch route : new ClientInputStateMachine.Dispatch[] {
         BUILDING, GEOMETRY, CANCEL
      }) {
         WorkspaceKeyboardSemantics.Decision decision = decideUndo(route, true, 3, false, false);
         assertEquals(WorkspaceKeyboardSemantics.Rejection.OTHER_SESSION_ACTIVE, decision.rejection(), route.name());
         assertTrue(decision.handsPressToWorldUndo(), route.name());
         assertFalse(decision.consumesPress(), route.name());
      }
   }

   /** A building or geometry session keeps its own undo. The local chain must not take it. */
   @Test
   void aLiveSessionKeepsTheUndoEvenWithStaleLocalHistory() {
      WorkspaceKeyboardSemantics.Decision building = decideUndo(BUILDING, false, 9, false, false);
      assertFalse(building.accepted());
      assertFalse(building.consumesPress());
      assertTrue(building.handsPressToWorldUndo());

      WorkspaceKeyboardSemantics.Decision geometry = decideUndo(GEOMETRY, false, 9, false, false);
      assertFalse(geometry.accepted());
      assertTrue(geometry.handsPressToWorldUndo());
   }

   /**
    * BUG-AI on the real entry path. A client-only workspace reaches Ctrl+Z with
    * the key route at VANILLA, because the operation flag is false once the last
    * part is gone. The press still belongs to the local chain.
    */
   @Test
   void theRealEntryPathLetsAnEmptyWorkspaceUndoItsOwnStep() {
      ClientInputStateMachine.State observed = ObservedInputState.stateFor(ObservedInputState.Sessions.idle());
      assertEquals(ClientInputStateMachine.State.IDLE, observed);
      ClientInputStateMachine.Dispatch route = machineIn(observed).dispatch(ClientInputStateMachine.InputKind.KEY);
      assertEquals(VANILLA, route);

      WorkspaceKeyboardSemantics.Decision decision = decideUndo(route, false, 1, false, false);

      assertTrue(decision.accepted(), "the local step must run");
      assertFalse(decision.handsPressToWorldUndo(), "the world request must not run");
      assertTrue(decision.consumesPress());
   }

   /** The same entry path without a local step still reaches the world request. */
   @Test
   void theRealEntryPathStillReachesTheWorldRequestWithoutALocalStep() {
      ClientInputStateMachine.Dispatch route = machineIn(
         ObservedInputState.stateFor(ObservedInputState.Sessions.idle())
      ).dispatch(ClientInputStateMachine.InputKind.KEY);

      WorkspaceKeyboardSemantics.Decision decision = decideUndo(route, false, 0, false, false);

      assertEquals(WorkspaceKeyboardSemantics.Rejection.NO_LOCAL_HISTORY, decision.rejection());
      assertTrue(decision.handsPressToWorldUndo());
   }

   /** A building session on the same path keeps its undo from the stale local chain. */
   @Test
   void theRealEntryPathKeepsABuildingUndo() {
      ObservedInputState.Sessions sessions = new ObservedInputState.Sessions(
         false, false, false, false, false, false, true
      );
      ClientInputStateMachine.State observed = ObservedInputState.stateFor(sessions);
      assertEquals(ClientInputStateMachine.State.BUILDING, observed);
      ClientInputStateMachine.Dispatch route = machineIn(observed).dispatch(ClientInputStateMachine.InputKind.KEY);

      WorkspaceKeyboardSemantics.Decision decision = decideUndo(route, false, 4, false, false);

      assertFalse(decision.accepted());
      assertTrue(decision.handsPressToWorldUndo());
   }

   /** The observation order keeps its priority for every flag combination. */
   @Test
   void theObservationOrderKeepsItsPriority() {
      assertEquals(ClientInputStateMachine.State.RESTORING, ObservedInputState.stateFor(
         new ObservedInputState.Sessions(true, true, true, true, true, true, true)));
      assertEquals(ClientInputStateMachine.State.PLACING, ObservedInputState.stateFor(
         new ObservedInputState.Sessions(false, true, true, true, true, true, true)));
      assertEquals(ClientInputStateMachine.State.ADJUSTING, ObservedInputState.stateFor(
         new ObservedInputState.Sessions(false, false, true, true, false, true, true)));
      assertEquals(ClientInputStateMachine.State.SELECTING, ObservedInputState.stateFor(
         new ObservedInputState.Sessions(false, false, true, false, false, true, true)));
      assertEquals(ClientInputStateMachine.State.GEOMETRY, ObservedInputState.stateFor(
         new ObservedInputState.Sessions(false, false, false, false, false, true, true)));
      assertEquals(ClientInputStateMachine.State.BUILDING, ObservedInputState.stateFor(
         new ObservedInputState.Sessions(false, false, false, false, false, false, true)));
      assertEquals(ClientInputStateMachine.State.IDLE, ObservedInputState.stateFor(
         ObservedInputState.Sessions.idle()));
   }

   /**
    * A busy state holds the press. The world undo request must not run during
    * another operation, and the local history must stay untouched.
    */
   @Test
   void busyWorkspaceStatesHoldThePress() {
      WorkspaceKeyboardSemantics.Decision blocked = decideUndo(BLOCKED, true, 4, false, false);
      assertEquals(WorkspaceKeyboardSemantics.Rejection.BLOCKED_INPUT_PHASE, blocked.rejection());
      assertTrue(blocked.consumesPress());
      assertFalse(blocked.handsPressToWorldUndo());

      WorkspaceKeyboardSemantics.Decision editing = decideUndo(OPERATION, true, 4, true, false);
      assertEquals(WorkspaceKeyboardSemantics.Rejection.EDIT_IN_PROGRESS, editing.rejection());
      assertTrue(editing.consumesPress());
      assertFalse(editing.handsPressToWorldUndo());

      WorkspaceKeyboardSemantics.Decision pending = decideUndo(OPERATION, true, 4, false, true);
      assertEquals(WorkspaceKeyboardSemantics.Rejection.SUBMISSION_PENDING, pending.rejection());
      assertTrue(pending.consumesPress());
      assertFalse(pending.handsPressToWorldUndo());
   }

   /** No busy combination reaches the world request, whatever the phase route is. */
   @Test
   void busyStatesNeverHandOffForEveryFactCombination() {
      for (ClientInputStateMachine.Dispatch route : new ClientInputStateMachine.Dispatch[] {
         OPERATION, VANILLA, BUILDING, GEOMETRY
      }) {
         for (boolean visible : new boolean[] {false, true}) {
            for (int depth : new int[] {0, 6}) {
               for (boolean editing : new boolean[] {false, true}) {
                  for (boolean pending : new boolean[] {false, true}) {
                     WorkspaceKeyboardSemantics.Decision decision =
                        decideUndo(route, visible, depth, editing, pending);
                     String label = route + "/" + visible + "/" + depth + "/" + editing + "/" + pending;
                     if (editing || pending) {
                        assertFalse(decision.accepted(), label);
                        assertFalse(decision.handsPressToWorldUndo(), label);
                        assertTrue(decision.consumesPress(), label);
                        continue;
                     }
                     if (route == OPERATION || route == VANILLA) {
                        assertEquals(depth > 0, decision.accepted(), label);
                        assertEquals(depth == 0, decision.handsPressToWorldUndo(), label);
                     } else {
                        assertFalse(decision.accepted(), label);
                        assertTrue(decision.handsPressToWorldUndo(), label);
                     }
                  }
               }
            }
         }
      }
   }

   @Test
   void aSubmissionOutranksTheLocalHistory() {
      WorkspaceKeyboardSemantics.Decision decision = decideUndo(OPERATION, true, 5, false, true);

      assertEquals(WorkspaceKeyboardSemantics.Rejection.SUBMISSION_PENDING, decision.rejection());
      assertFalse(decision.handsPressToWorldUndo());
   }

   @Test
   void anOpenEditOutranksTheLocalHistory() {
      WorkspaceKeyboardSemantics.Decision decision = decideUndo(OPERATION, true, 5, true, false);

      assertEquals(WorkspaceKeyboardSemantics.Rejection.EDIT_IN_PROGRESS, decision.rejection());
      assertFalse(decision.handsPressToWorldUndo());
   }

   @Test
   void negativeUndoDepthIsRejected() {
      org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
         new WorkspaceKeyboardSemantics.WorkspaceFacts(false, -1, false, false));
   }

   /**
    * Feeds the real dispatch values of every machine state into the undo
    * decision. A blocked state must hold the press. An operation session and the
    * free idle phase run the local step. Every other state leaves the press to
    * the world request.
    */
   @Test
   void undoRoutingAgreesWithTheRealInputDispatchValues() {
      for (Map.Entry<ClientInputStateMachine.State, ClientInputStateMachine.Dispatch> entry
         : realKeyRoutes().entrySet()) {
         ClientInputStateMachine.State state = entry.getKey();
         ClientInputStateMachine.Dispatch route = entry.getValue();
         WorkspaceKeyboardSemantics.Decision decision = decideUndo(route, true, 2, false, false);
         switch (route) {
            case OPERATION, VANILLA -> {
               assertTrue(decision.accepted(), state.name());
               assertTrue(decision.consumesPress(), state.name());
            }
            case BLOCKED -> {
               assertEquals(WorkspaceKeyboardSemantics.Rejection.BLOCKED_INPUT_PHASE, decision.rejection(), state.name());
               assertTrue(decision.consumesPress(), state.name());
               assertFalse(decision.handsPressToWorldUndo(), state.name());
            }
            default -> {
               assertEquals(WorkspaceKeyboardSemantics.Rejection.OTHER_SESSION_ACTIVE, decision.rejection(), state.name());
               assertTrue(decision.handsPressToWorldUndo(), state.name());
            }
         }
      }
   }

   /** The key route of every reachable input state, built through the machine. */
   private static Map<ClientInputStateMachine.State, ClientInputStateMachine.Dispatch> realKeyRoutes() {
      Map<ClientInputStateMachine.State, ClientInputStateMachine.Dispatch> routes = new LinkedHashMap<>();
      for (ClientInputStateMachine.State state : ClientInputStateMachine.State.values()) {
         ClientInputStateMachine machine = machineIn(state);
         assertEquals(state, machine.state());
         routes.put(state, machine.dispatch(ClientInputStateMachine.InputKind.KEY));
      }
      return routes;
   }

   private static ClientInputStateMachine machineIn(ClientInputStateMachine.State state) {
      ClientInputStateMachine machine = new ClientInputStateMachine();
      switch (state) {
         case IDLE -> { }
         case SUBMITTING -> {
            machine.observe(ClientInputStateMachine.State.BUILDING);
            assertTrue(machine.submit(UUID.randomUUID()));
         }
         case CANCELLING -> {
            machine.observe(ClientInputStateMachine.State.BUILDING);
            assertTrue(machine.cancel());
         }
         default -> machine.observe(state);
      }
      return machine;
   }
}
