package io.github.fastformer.client.operation;

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
   void allocatesTenSlotsAndReusesTheSmallestHoleWithoutRenumbering() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      for (int index = 1; index <= 10; index++) {
         assertTrue(workspace.addParts(List.of(part(index))));
      }
      assertFalse(workspace.addParts(List.of(part(99))));

      workspace.selectOnly(3);
      assertTrue(workspace.removeSelectedParts());
      assertTrue(workspace.addParts(List.of(part(99))));

      assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), workspace.partIds());
      assertEquals(new Vec3(99, 0, 0), workspace.part(3).orElseThrow().transform().translation());
      assertEquals(new Vec3(4, 0, 0), workspace.part(4).orElseThrow().transform().translation());
   }

   @Test
   void multiPartAddIsAtomicWhenCapacityIsInsufficient() {
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      for (int index = 1; index <= 9; index++) {
         assertTrue(workspace.addParts(List.of(part(index))));
      }

      assertFalse(workspace.addParts(List.of(part(20), part(21))));
      assertEquals(9, workspace.size());
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

   private static ClientSelectionPart part(int marker) {
      return ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)
         .withTranslation(new BlockPos(marker, 0, 0));
   }
}
