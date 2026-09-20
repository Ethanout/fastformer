package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientSelectionSessionTest {
   @Test
   void rejectedDraftEventsPreserveSessionLifecycleAndSnapshot() {
      for (SelectionSessionLifecycle.Phase phase : SelectionSessionLifecycle.Phase.values()) {
         for (OperationSelectionMode mode : OperationSelectionMode.values()) {
            var session = new ClientSelectionSession();
            session.setSelectionMode(mode);
            switch (phase) {
               case IDLE -> { }
               case POINTING -> session.onLifecycleEvent(SelectionSessionLifecycle.Event.BEGIN);
               case FOCUSED -> session.onLifecycleEvent(SelectionSessionLifecycle.Event.FOCUS);
               case SUBMITTING -> session.onLifecycleEvent(SelectionSessionLifecycle.Event.SUBMIT);
            }
            var draft = session.draftState();
            var owner = session.interactionOwnerId();
            long revision = session.workspace().revision();
            var button = mode == OperationSelectionMode.PRISM
               ? SelectionDraftEvent.Button.LEFT : SelectionDraftEvent.Button.RIGHT;

            assertEquals(SelectionDraftResult.REJECTED,
               session.onDraftEvent(new SelectionDraftEvent(button, BlockPos.ZERO, false)));
            assertEquals(SelectionDraftResult.REJECTED, session.onDraftEvent(null));
            session.addDraftPoint(null);

            assertEquals(phase, session.lifecyclePhase());
            org.junit.jupiter.api.Assertions.assertSame(draft, session.draftState());
            assertEquals(owner, session.interactionOwnerId());
            assertEquals(revision, session.workspace().revision());
         }
      }
   }

   @Test
   void acceptedDraftEventStartsPointing() {
      var session = new ClientSelectionSession();
      session.onLifecycleEvent(SelectionSessionLifecycle.Event.FOCUS);
      assertEquals(SelectionDraftResult.UPDATED,
         session.onDraftEvent(new SelectionDraftEvent(SelectionDraftEvent.Button.LEFT, BlockPos.ZERO, false)));
      assertEquals(SelectionSessionLifecycle.Phase.POINTING, session.lifecyclePhase());
      assertEquals(List.of(BlockPos.ZERO), session.draftPoints());
   }

   @Test
   void altStateOwnsTheGestureEvenWhenADraftPointAlreadyExists() {
      ClientSelectionSession session = new ClientSelectionSession();
      session.addDraftPoint(new BlockPos(1, 2, 3));
      session.setAltHeld(true);

      assertEquals(ClientSelectionState.ALT_FOCUSED, session.state());
   }

   @Test
   void pointingFocusedAndUnfocusedAreMutuallyExclusiveProjections() {
      ClientSelectionSession session = new ClientSelectionSession();
      assertEquals(ClientSelectionState.UNFOCUSED, session.state());

      session.addDraftPoint(BlockPos.ZERO);
      session.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      assertEquals(ClientSelectionState.POINTING, session.state());

      session.clearDraft();
      assertEquals(ClientSelectionState.FOCUSED, session.state());
   }

   @Test
   void selectionModeIsSessionOwnedAndDefaultsToCuboid() {
      ClientSelectionSession session = new ClientSelectionSession();
      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
      session.setSelectionMode(OperationSelectionMode.PRISM);
      assertEquals(OperationSelectionMode.PRISM, session.selectionMode());
   }

   @Test
   void selectionAndUndoChangeTheProjectionWithoutRefreshCallbacks() {
      ClientSelectionSession session = new ClientSelectionSession();
      session.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      assertEquals(ClientSelectionState.FOCUSED, session.state());

      assertTrue(session.workspace().clearSelectionForNewDraft());
      assertEquals(ClientSelectionState.UNFOCUSED, session.state());
      assertTrue(session.workspace().undo());
      assertEquals(ClientSelectionState.FOCUSED, session.state());
   }

   @Test
   void inspectingServerPointingDoesNotStoreAnotherStateOrChangeRevision() {
      ClientSelectionSession session = new ClientSelectionSession();
      long revision = session.workspace().revision();

      assertEquals(ClientSelectionState.POINTING, session.state(true));
      assertEquals(ClientSelectionState.UNFOCUSED, session.state(false));
      assertEquals(ClientSelectionState.UNFOCUSED, session.state());
      assertEquals(revision, session.workspace().revision());
      assertEquals(0, session.workspace().undoSize());
      assertTrue(session.draftPoints().isEmpty());
   }

   @Test
   void serverPointingDoesNotOverrideExistingParts() {
      ClientSelectionSession session = new ClientSelectionSession();
      session.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      assertEquals(ClientSelectionState.FOCUSED, session.state(true));
      session.workspace().clearSelectionForNewDraft();
      assertEquals(ClientSelectionState.UNFOCUSED, session.state(true));
   }

   @Test
   void clearingDraftDoesNotDeletePartsButEnvironmentCleanupClearsBoth() {
      ClientSelectionSession session = new ClientSelectionSession();
      session.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      session.addDraftPoint(BlockPos.ZERO);
      session.clearDraft();
      assertEquals(1, session.workspace().size());
      assertEquals(ClientSelectionState.FOCUSED, session.state());

      session.setSelectionMode(OperationSelectionMode.PRISM);
      session.addDraftPoint(BlockPos.ZERO);
      session.setAltHeld(true);
      session.workspace().setLocked(true);
      session.clearLiveInteraction();
      assertTrue(session.workspace().isEmpty());
      assertTrue(session.draftPoints().isEmpty());
      assertEquals(ClientSelectionState.UNFOCUSED, session.state());
      assertEquals(OperationSelectionMode.PRISM, session.selectionMode());
   }

   @Test
   void cuboidDraftExpansionKeepsItsTwoInputPoints() {
      BlockPos first = new BlockPos(2, 3, 4);
      BlockPos second = new BlockPos(5, 6, 7);
      ClientSelectionSession session = new ClientSelectionSession();
      session.addDraftPoint(first);
      session.addDraftPoint(second);

      assertTrue(session.expandDraftTo(new BlockPos(-1, 9, 3)));
      assertEquals(first, session.draftAt(0));
      assertEquals(second, session.draftAt(1));
      assertEquals(List.of(new BlockPos(-1, 3, 3), new BlockPos(5, 9, 7)), session.selectionDraftPoints());
   }

   @Test
   void removingDraftPointsRecomputesTheAuthoritativeBounds() {
      ClientSelectionSession session = new ClientSelectionSession();
      session.addDraftPoint(new BlockPos(2, 2, 2));
      session.addDraftPoint(new BlockPos(8, 8, 8));
      session.addDraftPoint(new BlockPos(-4, 10, 1));

      assertEquals(new BlockPos(-4, 2, 1), session.draftMinPoint());
      assertEquals(new BlockPos(8, 10, 8), session.draftMaxPoint());
      assertEquals(new BlockPos(-4, 10, 1), session.removeLastDraftPoint());
      assertEquals(new BlockPos(2, 2, 2), session.draftMinPoint());
      assertEquals(new BlockPos(8, 8, 8), session.draftMaxPoint());

      session.removeLastDraftPoint();
      session.removeLastDraftPoint();
      assertNull(session.draftMinPoint());
      assertNull(session.draftMaxPoint());
   }

   @Test
   void restoredDraftBoundsAreNormalizedWithoutChangingInputPoints() {
      ClientSelectionSession session = new ClientSelectionSession();
      BlockPos first = new BlockPos(3, 4, 5);
      BlockPos second = new BlockPos(6, 7, 8);
      session.addDraftPoint(first);
      session.addDraftPoint(second);

      session.restoreDraftBounds(new BlockPos(10, -2, 7), new BlockPos(-3, 12, 1));

      assertEquals(first, session.draftAt(0));
      assertEquals(second, session.draftAt(1));
      assertEquals(new BlockPos(-3, -2, 1), session.draftMinPoint());
      assertEquals(new BlockPos(10, 12, 7), session.draftMaxPoint());
   }

   @Test
   void undoRestoreKeepsAltAndPartsWhileEnvironmentRestoreDropsAlt() {
      var session = new ClientSelectionSession();
      session.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      session.addDraftPoint(BlockPos.ZERO);
      session.addDraftPoint(new BlockPos(2, 2, 2));
      session.expandDraftTo(new BlockPos(-4, 9, 1));
      var saved = session.draftState();
      session.clearDraft();
      session.setAltHeld(true);
      session.restoreDraftEdit(saved);
      assertTrue(session.altHeld());
      assertEquals(saved, session.draftState());
      assertEquals(1, session.workspace().size());
      session.restoreDraftState(saved);
      org.junit.jupiter.api.Assertions.assertFalse(session.altHeld());
      assertEquals(saved, session.draftState());
      assertEquals(1, session.workspace().size());
   }
}
