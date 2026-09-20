package io.github.fastformer.client.operation.workspace;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ClientOperationWorkspaceTest {
   @Test
   void allocatesUnlimitedSlotsAndReusesTheSmallestHoleWithoutRenumbering() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      for (int index = 1; index <= 10; index++) {
         assertTrue(workspace.addParts(List.of(part(index))));
      }
      assertTrue(workspace.addParts(List.of(part(99))));

      workspace.selectOnly(3);
      assertTrue(workspace.removeSelectedParts());
      assertTrue(workspace.addParts(List.of(part(99))));

      assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), workspace.partIds());
      assertEquals(new Vec3(99, 0, 0), workspace.part(3).orElseThrow().transform().translation());
      assertEquals(new Vec3(4, 0, 0), workspace.part(4).orElseThrow().transform().translation());
   }

   @Test
   void multiPartAddIsAtomicAndUnlimited() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      for (int index = 1; index <= 9; index++) {
         assertTrue(workspace.addParts(List.of(part(index))));
      }

      assertTrue(workspace.addParts(List.of(part(20), part(21))));
      assertEquals(11, workspace.size());
   }

   @Test
   void selectionTracksActivePartAndSupportsToggleAndSelectAll() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1), part(2), part(3)));

      workspace.selectOnly(1);
      workspace.toggleSelected(2);
      assertEquals(Set.of(1, 2), workspace.selectedIds());
      assertEquals(2, workspace.activeId());

      workspace.toggleSelected(2);
      assertEquals(Set.of(1), workspace.selectedIds());
      assertEquals(1, workspace.activeId());

      workspace.selectAll();
      assertEquals(Set.of(1, 2, 3), workspace.selectedIds());
   }

   @Test
   void completeGestureCreatesOneUndoEntryForAllAffectedParts() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1), part(2)));
      workspace.selectAll();
      workspace.clearHistory();

      assertTrue(workspace.beginEdit());
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(5, 0, 0)));
      workspace.updatePart(workspace.part(2).orElseThrow().withTranslation(new BlockPos(6, 0, 0)));
      assertTrue(workspace.finishEdit());
      assertEquals(1, workspace.undoSize());

      assertTrue(workspace.undo());
      assertEquals(new Vec3(1, 0, 0), workspace.part(1).orElseThrow().transform().translation());
      assertEquals(new Vec3(2, 0, 0), workspace.part(2).orElseThrow().transform().translation());
      assertEquals(Set.of(1, 2), workspace.selectedIds());
   }

   @Test
   void inputEventAndTransformShareOneChronologicalHistory() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1)));
      workspace.clearHistory();
      workspace.selectOnly(1);
      workspace.pushEvent(() -> workspace.restoreSelectionStateWithoutHistory(new ClientOperationWorkspace.SelectionState(Set.of(), 0)));

      assertTrue(workspace.beginEdit());
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(4, 0, 0)));
      assertTrue(workspace.finishEdit());

      assertTrue(workspace.undo());
      assertEquals(new Vec3(1, 0, 0), workspace.part(1).orElseThrow().transform().translation());
      assertTrue(workspace.undo());
      assertEquals(Set.of(), workspace.selectedIds());
   }

   @Test
   void compositePartInsertCanShareOneUndoEntryWithItsDraftEvent() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1)));
      workspace.selectOnly(1);
      workspace.clearHistory();
      ClientOperationWorkspace.SelectionState before = workspace.selectionState();
      Set<Integer> beforeParts = workspace.partIds();

      assertTrue(workspace.clearSelectionForNewDraftWithoutHistory());
      assertTrue(workspace.addPartsWithoutHistory(List.of(part(2))));
      workspace.pushEvent(() -> {
         workspace.restoreParts(beforeParts);
         workspace.restoreSelectionStateWithoutHistory(before);
      });

      assertEquals(1, workspace.undoSize());
      assertTrue(workspace.undo());
      assertEquals(beforeParts, workspace.partIds());
      assertEquals(before.ids(), workspace.selectedIds());
      assertEquals(before.activeId(), workspace.activeId());
   }

   @Test
   void removingSelectedPartsCanBeUndoneAsOneWorkspaceCommand() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1), part(2), part(3)));
      workspace.selectOnly(1);
      workspace.toggleSelected(3);
      workspace.clearHistory();

      assertTrue(workspace.removeSelectedParts());
      assertEquals(Set.of(2), workspace.partIds());
      assertTrue(workspace.undo());
      assertEquals(Set.of(1, 2, 3), workspace.partIds());
      assertEquals(Set.of(1, 3), workspace.selectedIds());
   }

   @Test
   void staleEditTokenCannotFinishOrCancelANewerGesture() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1)));

      assertTrue(workspace.beginEdit());
      ClientOperationWorkspace.EditToken first = workspace.activeEditToken();
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(2, 0, 0)));
      assertTrue(workspace.finishEdit(first));

      assertTrue(workspace.beginEdit());
      ClientOperationWorkspace.EditToken second = workspace.activeEditToken();
      assertFalse(workspace.finishEdit(first));
      assertFalse(workspace.cancelEdit(first));
      assertTrue(workspace.ownsEdit(second));
      assertTrue(workspace.cancelEdit(second));
   }

   @Test
   void revisionAdvancesForSelectionAndContentMutations() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      long empty = workspace.revision();
      workspace.addParts(List.of(part(1), part(2)));
      long added = workspace.revision();
      workspace.selectOnly(1);
      long selected = workspace.revision();
      assertTrue(workspace.beginEdit());
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(8, 0, 0)));

      assertTrue(added > empty);
      assertTrue(selected > added);
      assertTrue(workspace.revision() > selected);
   }

   @Test
   void lockTransitionsNotifyAndBlockMutations() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1)));
      long beforeLock = workspace.revision();

      workspace.setLocked(true);
      assertTrue(workspace.locked());
      assertTrue(workspace.revision() > beforeLock);
      assertFalse(workspace.beginEdit());

      long beforeUnlock = workspace.revision();
      workspace.setLocked(false);
      assertFalse(workspace.locked());
      assertTrue(workspace.revision() > beforeUnlock);
   }

   @Test
   void sourceMaskOnlyReplacesTransformedOrDeletedWorldParts() {
      ClientSelectionPart original = ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD);
      assertFalse(original.masksSourceBlocks());

      ClientSelectionPart moved = original.withTranslation(new BlockPos(1, 0, 0));
      assertTrue(moved.masksSourceBlocks());
      assertFalse(moved.withTranslation(BlockPos.ZERO).masksSourceBlocks());
      assertTrue(original.withPendingDelete(true).masksSourceBlocks());
      assertFalse(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)
         .withTranslation(new BlockPos(1, 0, 0)).masksSourceBlocks());
   }

   @Test
   void repeatedNoOpSelectionsDoNotGrowTheHistory() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1), part(2)));
      workspace.clearHistory();

      workspace.selectOnly(1);
      workspace.selectOnly(1);
      workspace.selectAll();
      workspace.selectAll();

      // Both edits changed the live state once and were recorded once.
      assertEquals(2, workspace.undoSize());
      assertTrue(workspace.undo());
      assertEquals(Set.of(1), workspace.selectedIds());
      assertTrue(workspace.undo());
      assertEquals(Set.of(1, 2), workspace.selectedIds());
      assertFalse(workspace.undo());
   }

   @Test
   void theHistoryBudgetDropsTheOldestWorkspaceEdits() {
      ClientOperationWorkspace bounded = new ClientOperationWorkspace(3, 10_000_000);
      ClientOperationWorkspace unbounded = new ClientOperationWorkspace();
      for (ClientOperationWorkspace workspace : List.of(bounded, unbounded)) {
         workspace.addParts(List.of(part(1)));
         workspace.addParts(List.of(part(2)));
         workspace.addParts(List.of(part(3)));
         workspace.addParts(List.of(part(4)));
      }

      assertEquals(4, unbounded.undoSize());
      assertEquals(3, bounded.undoSize());
      assertEquals(1L, bounded.historyDiscardedRecords());
      assertTrue(bounded.historyWeight() < unbounded.historyWeight());

      // The remaining nodes keep their order, and the dropped node is gone, so
      // the fourth undo of the bounded workspace reports nothing to undo.
      assertTrue(bounded.undo());
      assertEquals(Set.of(1, 2, 3), bounded.partIds());
      assertTrue(bounded.undo());
      assertTrue(bounded.undo());
      assertEquals(Set.of(1), bounded.partIds());
      assertFalse(bounded.undo());
   }

   @Test
   void theHistoryAccountsTheDeclaredRetentionOfEachNode() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace(100, 10_000_000);

      workspace.addParts(List.of(part(1)));
      // The recorded snapshot is the empty state before the first part.
      assertEquals(1L, workspace.historyWeight());

      workspace.pushEvent(() -> {});
      assertEquals(2L, workspace.historyWeight());

      workspace.addParts(List.of(part(2)));
      // The snapshot retains the part, its interaction identity, and its selection.
      assertEquals(6L, workspace.historyWeight());

      workspace.addParts(List.of(part(3)));
      assertEquals(12L, workspace.historyWeight());
      assertEquals(4, workspace.undoSize());
   }

   @Test
   void aPayloadSharedByTwoDeclaredEventsIsChargedOnce() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace(100, 10_000_000);
      Object holder = new Object();
      ClientOperationEventStack.Retention retention = ClientOperationEventStack.Retention.of(
         1, List.of(ClientOperationEventStack.SharedPayload.of(holder, 5))
      );

      workspace.pushEvent(() -> {}, retention);
      assertEquals(6L, workspace.historyWeight());

      workspace.pushEvent(() -> {}, retention);
      // The second event keeps the same holder alive, so it only adds its own
      // unit. This is the "count the shared snapshot, not every reference" rule.
      assertEquals(7L, workspace.historyWeight());

      assertTrue(workspace.undo());
      assertEquals(6L, workspace.historyWeight());
      assertTrue(workspace.undo());
      assertEquals(0L, workspace.historyWeight());
   }

   @Test
   void clearingTheHistoryReleasesItsWeight() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1)));
      workspace.addParts(List.of(part(2)));
      assertTrue(workspace.historyWeight() > 0L);

      workspace.clearHistory();

      assertEquals(0L, workspace.historyWeight());
      assertEquals(0, workspace.undoSize());
   }

   @Test
   void reusedPartNumberHasANewInteractionIdentity() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1)));
      long original = workspace.interactionId(1);
      assertTrue(workspace.removeSelectedParts());
      org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> workspace.interactionId(1));
      workspace.addParts(List.of(part(2)));
      long replacement = workspace.interactionId(1);
      assertTrue(replacement > original);
      assertTrue(workspace.undo());
      assertTrue(workspace.undo());
      assertEquals(original, workspace.interactionId(1));
      assertTrue(workspace.removeSelectedParts());
      workspace.addParts(List.of(part(3)));
      assertTrue(workspace.interactionId(1) > replacement);
   }

   @Test
   void editsAndTheirUndoPreserveInteractionIdentity() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1)));
      long identity = workspace.interactionId(1);
      assertTrue(workspace.beginEdit());
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(8, 0, 0)));
      assertTrue(workspace.finishEdit());
      assertEquals(identity, workspace.interactionId(1));
      assertTrue(workspace.undo());
      assertEquals(identity, workspace.interactionId(1));
      assertTrue(workspace.beginEdit());
      workspace.removePartDuringEdit(1);
      workspace.cancelEdit();
      assertEquals(identity, workspace.interactionId(1));
   }

   @Test
   void restoringDurableDraftCreatesNewTransientIdentities() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1)));
      long identity = workspace.interactionId(1);
      var draft = workspace.draftState();
      workspace.restoreDraftState(draft);
      assertTrue(workspace.interactionId(1) > identity);
      long restored = workspace.interactionId(1);
      workspace.clear();
      workspace.addParts(List.of(part(1)));
      assertTrue(workspace.interactionId(1) > restored);
   }

   @Test
   void compositePartRemovalAlsoRemovesInteractionIdentity() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1), part(2)));
      long remaining = workspace.interactionId(1);
      long removed = workspace.interactionId(2);
      workspace.restoreParts(Set.of(1));
      assertEquals(remaining, workspace.interactionId(1));
      org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> workspace.interactionId(2));
      workspace.addParts(List.of(part(3)));
      assertTrue(workspace.interactionId(2) > removed);
   }

   @Test
   void editingTokensCannotCrossWorkspaceOwners() {
      var first = new ClientOperationWorkspace();
      var second = new ClientOperationWorkspace();
      first.addParts(List.of(part(1)));
      second.addParts(List.of(part(2)));
      assertTrue(first.beginEdit());
      assertTrue(second.beginEdit());
      var stale = first.activeEditToken();
      var current = second.activeEditToken();
      assertFalse(second.ownsEdit(stale));
      assertFalse(second.finishEdit(stale));
      assertFalse(second.cancelEdit(stale));
      assertTrue(second.ownsEdit(current));
      assertTrue(first.ownsEdit(stale));
   }

   @Test
   void clearingAndRestoringCannotReviveAnOldEditToken() {
      var workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(1)));
      var draft = workspace.draftState();
      assertTrue(workspace.beginEdit());
      var stale = workspace.activeEditToken();
      workspace.restoreDraftState(draft);
      assertTrue(workspace.beginEdit());
      var current = workspace.activeEditToken();
      assertFalse(workspace.cancelEdit(stale));
      assertFalse(workspace.finishEdit(stale));
      assertTrue(workspace.ownsEdit(current));
   }

   private static ClientSelectionPart part(int marker) {
      return ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)
         .withTranslation(new BlockPos(marker, 0, 0));
   }
}
