package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointPress;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.selection.ClientSelectionStack;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionStackTest {
   @Test void cancellingRemoteSecondPointWorksForEverySelectionMode() {
      for (var mode : OperationSelectionMode.values()) {
         reset();
         assertTrue(ClientOperationController.synchronize(
            io.github.fastformer.network.payload.operation.OperationPreviewPayload.inactive(0)));
         ClientOperationController.onClientTick();
         var preview = io.github.fastformer.network.payload.operation.OperationPreviewPayload.active(
            1L, false, true, List.of(A), BlockPos.ZERO, BlockPos.ZERO, mode, 0, -1, 0,
            io.github.fastformer.fastplace.selection.OperationMode.MOVE,
            io.github.fastformer.fastplace.selection.OperationStageMode.TRANSFORM,
            BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, net.minecraft.world.phys.Vec3.ZERO, false, false, false);
         assertTrue(ClientOperationController.synchronize(preview));
         assertTrue(ClientOperationController.remoteSelectionPointing());
         assertTrue(ClientOperationController.cancelLastSelection());
         assertFalse(ClientOperationController.selectionSessionActive());
         assertFalse(ClientOperationController.selectionDraftActive());
         assertTrue(ClientOperationController.undo());
         var restored = ClientOperationController.selectionDraft();
         assertEquals(List.of(A), restored.points());
         assertEquals(mode == OperationSelectionMode.CUBOID, restored.secondPointOnly());
      }
   }

   @Test void idleSmartLeftClickDoesNotStartOrQueueASelection() {
      var preference = io.github.fastformer.client.operation.selection.SelectionToolPreference.get();
      try {
         io.github.fastformer.client.operation.selection.SelectionToolPreference.set(OperationSelectionMode.SMART);
         assertTrue(FastPlaceClientInput.captureInitialSelection(0, A, false, false, 1));
         assertTrue(FastPlaceClientInput.captureInitialSelection(0, null, true, false, 2));
         drain();
         assertFalse(ClientOperationController.selectionSessionActive());
         assertFalse(ClientOperationController.selectionDraftActive());
         assertTrue(workspace().isEmpty());
         assertEquals(io.github.fastformer.client.input.state.ClientInputStateMachine.State.IDLE,
            FastPlaceClientInput.inputSession().routing.state());
      } finally {
         io.github.fastformer.client.operation.selection.SelectionToolPreference.set(preference);
      }
   }

   @Test void deselectAllPreservesTheSelectionStackAndCanBeUndone() {
      var first = addSelection(0);
      var second = addSelection(10);
      ClientOperationController.selectAllWorkspaceParts();
      ClientOperationController.deselectAllWorkspaceParts();
      assertTrue(workspace().selectedIds().isEmpty());
      assertEquals(0, workspace().activeId());
      assertEquals(List.of(first, second), workspace().parts());
      assertTrue(ClientOperationController.undo());
      assertEquals(java.util.Set.of(first.id(), second.id()), workspace().selectedIds());
   }
   @Test void qExitsWhenItPopsTheLastSelectionEntry() {
      ClientOperationController.enterLocalSelectionSession();
      ClientOperationController.handleCreateClick(0, new BlockPos(1, 2, 3));
      assertTrue(ClientOperationController.cancelLastSelection());
      assertFalse(ClientOperationController.selectionSessionActive());
      assertFalse(ClientOperationController.active());
      assertFalse(ClientOperationController.selectionDraftActive());
      assertTrue(ClientOperationController.undo());
      assertTrue(ClientOperationController.selectionSessionActive());
      assertEquals(new BlockPos(1, 2, 3), ClientOperationController.selectionDraft().firstPoint());
   }
   private static final BlockPos A = new BlockPos(1, 2, 3);
   private static final BlockPos B = new BlockPos(4, 5, 6);

   @BeforeEach void reset() {
      ClientOperationController.onDisconnected();
      FastPlaceClientInput.inputSession().reset();
   }

   @AfterEach void clear() { reset(); }

   @Test void eachInitialButtonKeepsItsPointRoleUntilTheMissingPointArrives() {
      for (int firstButton : List.of(0, 1, 2)) {
         for (int completingButton : firstButton == 1 ? List.of(0, 2) : List.of(1, 2)) {
            reset();
            assertTrue(FastPlaceClientInput.captureInitialSelection(firstButton, A, false, false, 1));
            drain();
            var draft = ClientOperationController.selectionDraft();
            assertEquals(firstButton == 1 ? null : A, draft.firstPoint());
            assertEquals(firstButton == 1 ? A : null, draft.secondPoint());
            post(completingButton, B);
            drain();
            var part = workspace().latestPart().orElseThrow();
            assertEquals(firstButton == 1 ? B : A, part.selection().point1());
            assertEquals(firstButton == 1 ? A : B, part.selection().point2());
            assertTrue(part.canAdjustGeometry());
            assertFalse(ClientOperationController.selectionDraftActive());
         }
      }
   }

   @Test void repeatedRightReplacesOnlyPointTwoAndUndoPreservesItsRole() {
      assertTrue(ClientOperationController.handleCreateClick(1, A));
      assertTrue(ClientOperationController.handleCreateClick(1, B));
      assertTrue(workspace().isEmpty());
      assertNull(ClientOperationController.selectionDraft().firstPoint());
      assertEquals(B, ClientOperationController.selectionDraft().secondPoint());
      assertTrue(ClientOperationController.undo());
      assertEquals(A, ClientOperationController.selectionDraft().secondPoint());
      assertTrue(ClientOperationController.handleCreateClick(0, B));
      assertEquals(A, workspace().latestPart().orElseThrow().selection().point2());
   }

   @Test void cancellingNewestRevealsTheUnchangedAdjustablePreviousSelection() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(workspace().parts().stream().allMatch(ClientSelectionPart::canAdjustGeometry));
      ClientOperationController.selectWorkspacePart(first.id(), false);
      assertTrue(ClientOperationController.cancelLastSelection());
      assertTrue(workspace().part(second.id()).isEmpty());
      assertEquals(first, workspace().latestPart().orElseThrow());
      assertEquals(first.id(), workspace().activeId());
      assertFalse(ClientOperationController.canStartSelectionDraft());
   }

   @Test void pendingPointCancellationLeavesEveryExistingSelectionUntouched() {
      addSelection(0);
      addSelection(10);
      var before = workspace().draftState();
      assertTrue(ClientOperationController.handleCreateClick(1, new BlockPos(30, 0, 0)));
      assertTrue(ClientOperationController.cancelLastSelection());
      assertEquals(before, workspace().draftState());
      assertFalse(ClientOperationController.selectionDraftActive());
      assertTrue(ClientOperationController.undo());
      assertTrue(ClientOperationController.selectionDraft().secondPointOnly());
      assertEquals(before, workspace().draftState());
   }

   @Test void fixingNewestDoesNotFixOlderSelectionsOrAllowMiddleToSkipTheTop() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(ClientOperationController.fixActiveSelection());
      assertFalse(workspace().part(second.id()).orElseThrow().canAdjustGeometry());
      assertEquals(first, workspace().part(first.id()).orElseThrow());
      assertTrue(ClientOperationController.canStartSelectionDraft());
      assertTrue(ClientOperationController.cancelLastSelection());
      assertFalse(ClientOperationController.canStartSelectionDraft());
      assertEquals(first, workspace().latestPart().orElseThrow());
   }

   @Test void enterCompletesPreviewAndSubmitsAllInOnePress() {
      addSelection(0);
      assertTrue(ClientOperationController.handleCreateClick(1, B));
      assertTrue(ClientOperationController.confirmSelection(null, A));
      assertFalse(ClientOperationController.selectionDraftActive());
      assertTrue(workspace().isEmpty());
      assertFalse(ClientOperationController.undo());
   }

   @Test void newCuboidPointFixesSmartTailAndUndoRestoresTheActiveGroup() {
      var smart = smartPart();
      workspace().addParts(List.of(smart));
      var before = workspace().draftState();
      assertTrue(ClientOperationController.handleCreateClick(0, B));
      assertFalse(workspace().parts().getFirst().smartEditable());
      assertTrue(ClientOperationController.undo());
      assertEquals(before, workspace().draftState());
      assertTrue(workspace().parts().getFirst().smartEditable());
   }

   @Test void scrollMovementFixesSmartGroupAndUndoRestoresIt() {
      workspace().addParts(List.of(smartPart()));
      var before = workspace().draftState();
      assertTrue(ClientOperationController.moveSelected(BlockPos.ZERO.east()));
      assertFalse(workspace().parts().getFirst().smartEditable());
      assertTrue(workspace().parts().getFirst().transformed());
      assertTrue(ClientOperationController.undo());
      assertEquals(before, workspace().draftState());
   }

   private static ClientSelectionPart smartPart() {
      var volume = new io.github.fastformer.fastplace.selection.OperationSelectionVolume(OperationSelectionMode.SMART,
         new net.minecraft.world.phys.AABB(A), null, List.of(), 0, null, null);
      return new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD, volume, java.util.Map.of(),
         io.github.fastformer.workspace.model.WorkspaceTransform.IDENTITY, false);
   }

   @Test void enterIncludesUnselectedPartsWithoutLeavingASessionUndo() {
      var first = addSelection(0);
      addSelection(10);
      assertTrue(ClientOperationController.fixActiveSelection());
      assertTrue(ClientOperationController.confirmSelection(null, null));
      assertTrue(workspace().isEmpty());
      assertTrue(workspace().selectedIds().isEmpty());
      assertFalse(ClientOperationController.undo());
      assertTrue(workspace().isEmpty());
   }

   @Test void controlEnterCompletesTheDraftAndSubmitsEveryGroup() {
      addSelection(0);
      assertTrue(ClientOperationController.handleCreateClick(0, A));
      assertTrue(ClientOperationController.confirmSelection(null, B));
      assertTrue(workspace().isEmpty());
      assertFalse(ClientOperationController.selectionDraftActive());
      assertFalse(ClientOperationController.undo());
      assertTrue(workspace().isEmpty());
   }

   @Test void reusedDisplayNumberDoesNotChangeCreationOrderAfterUndoOrRestore() {
      var first = addSelection(0);
      var second = addSelection(10);
      workspace().selectOnly(first.id());
      assertTrue(workspace().removeSelectedParts());
      var third = addSelection(20);
      assertEquals(first.id(), third.id());
      assertEquals(List.of(second, third), workspace().parts());
      var stored = workspace().draftState();
      assertTrue(ClientOperationController.cancelLastSelection());
      assertEquals(List.of(second), workspace().parts());
      assertTrue(ClientOperationController.undo());
      assertEquals(third, workspace().latestPart().orElseThrow());
      workspace().restoreDraftState(stored);
      assertEquals(third, workspace().latestPart().orElseThrow());
      assertTrue(ClientOperationController.cancelLastSelection());
      assertEquals(second.id(), workspace().activeId());
   }

   @Test void cancellingDuringAnOpenAdjustmentReleasesTheEditAndRevealsPreviousSelection() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(workspace().beginEdit());
      workspace().updatePart(second.withTranslation(new BlockPos(4, 0, 0)));
      assertTrue(ClientOperationController.cancelLastSelection());
      assertFalse(workspace().editing());
      assertEquals(List.of(first), workspace().parts());
      assertEquals(first.id(), workspace().activeId());
   }

   @Test void completedAndIncompleteSelectionsShareTheSameStackTop() {
      var first = addSelection(0);
      assertEquals(new ClientSelectionStack.Complete(first), workspace().selections().peek().orElseThrow());
      assertTrue(ClientOperationController.handleCreateClick(0, A));
      var pending = assertInstanceOf(ClientSelectionStack.Pending.class, workspace().selections().peek().orElseThrow());
      assertEquals(A, pending.points().firstPoint());
      assertNull(pending.points().secondPoint());
      assertTrue(workspace().selections().topPart().isEmpty());
      assertTrue(ClientOperationController.handleCreateClick(2, B));
      var complete = assertInstanceOf(ClientSelectionStack.Complete.class, workspace().selections().peek().orElseThrow());
      assertEquals(A, complete.part().selection().point1());
      assertEquals(B, complete.part().selection().point2());
      assertEquals(2, workspace().size());
      assertEquals(first, workspace().parts().getFirst());
   }

   @Test void consecutivePopsAndUndoRestoreEachEntryWithItsPointRole() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(ClientOperationController.handleCreateClick(1, A));
      assertTrue(ClientOperationController.cancelLastSelection());
      assertEquals(second, workspace().selections().topPart().orElseThrow());
      assertTrue(ClientOperationController.cancelLastSelection());
      assertEquals(first, workspace().selections().topPart().orElseThrow());
      assertTrue(ClientOperationController.undo());
      assertEquals(second, workspace().selections().topPart().orElseThrow());
      assertTrue(ClientOperationController.undo());
      var pending = assertInstanceOf(ClientSelectionStack.Pending.class, workspace().selections().peek().orElseThrow());
      assertNull(pending.points().firstPoint());
      assertEquals(A, pending.points().secondPoint());
      assertTrue(ClientOperationController.handleCreateClick(2, B));
      assertEquals(3, workspace().size());
      assertEquals(B, workspace().latestPart().orElseThrow().selection().point1());
      assertEquals(A, workspace().latestPart().orElseThrow().selection().point2());
   }

   @Test void editingAnOlderPartDoesNotMoveItAboveThePendingStackEntry() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(ClientOperationController.handleCreateClick(1, A));
      var pending = workspace().selections().peek().orElseThrow();
      assertTrue(workspace().beginEdit());
      workspace().updatePart(first.fixed());
      assertTrue(workspace().finishEdit());
      assertSame(pending, workspace().selections().peek().orElseThrow());
      assertTrue(ClientOperationController.cancelLastSelection());
      assertEquals(second, workspace().selections().topPart().orElseThrow());
      assertFalse(workspace().part(first.id()).orElseThrow().canAdjustGeometry());
   }

   @Test void poppingAnEmptyStackKeepsTheSessionAvailableForEitherPoint() {
      assertTrue(ClientOperationController.cancelLastSelection());
      assertTrue(ClientOperationController.handleCreateClick(1, A));
      assertTrue(ClientOperationController.cancelLastSelection());
      assertTrue(ClientOperationController.cancelLastSelection());
      assertTrue(workspace().selections().peek().isEmpty());
      assertTrue(ClientOperationController.handleCreateClick(0, B));
      assertEquals(B, ClientOperationController.selectionDraft().firstPoint());
   }

   @Test void middleTargetsTheTopEvenWhenAnOlderSelectionIsActive() {
      var first = addSelection(0);
      var second = addSelection(10);
      workspace().selectOnly(first.id());
      var owner = ClientOperationController.interactionScene().owner();
      var middle = new SelectionPointPress(owner, second.id(), workspace().interactionId(second.id()), 2, false, A, 1);
      assertTrue(middle.matches(owner, workspace()));
      assertTrue(ClientOperationController.handleCreateClick(1, A));
      assertFalse(middle.matches(owner, workspace()));
      assertTrue(ClientOperationController.cancelLastSelection());
      assertTrue(middle.matches(owner, workspace()));
      addSelection(20);
      assertFalse(middle.matches(owner, workspace()));
   }

   @Test void delayedMiddleCannotModifyANewSelectionWithAReusedDisplayNumber() {
      addSelection(0);
      var second = addSelection(10);
      var owner = ClientOperationController.interactionScene().owner();
      var middle = new SelectionPointPress(owner, second.id(), workspace().interactionId(second.id()), 2, false, A, 1);
      assertTrue(ClientOperationController.cancelLastSelection());
      var replacement = addSelection(20);
      assertEquals(second.id(), replacement.id());
      assertFalse(middle.matches(owner, workspace()));
   }

   private static ClientSelectionPart addSelection(int x) {
      assertTrue(ClientOperationController.handleCreateClick(0, new BlockPos(x, 0, 0)));
      assertTrue(ClientOperationController.handleCreateClick(1, new BlockPos(x + 2, 2, 2)));
      return workspace().latestPart().orElseThrow();
   }

   private static ClientOperationWorkspace workspace() { return ClientOperationController.workspace(); }

   private static void post(int button, BlockPos point) {
      FastPlaceClientInput.inputSession().captureSelectionPress(new SelectionDraftPress(
         ClientOperationController.interactionScene().owner(), OperationSelectionMode.CUBOID, button,
         point, false, false, 2));
   }

   private static void drain() {
      var input = FastPlaceClientInput.inputSession();
      input.drainPhysicalEvents(() -> true, key -> fail(), scroll -> fail(),
         event -> SelectionInputDispatcher.dispatch(null, input, event));
   }
}
