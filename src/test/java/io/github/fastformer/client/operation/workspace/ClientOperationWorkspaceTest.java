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

   private static ClientSelectionPart part(int marker) {
      return ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)
         .withTranslation(new BlockPos(marker, 0, 0));
   }
}
