package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientSessionSystem;

import static org.junit.jupiter.api.Assertions.*;

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
         assertTrue(ClientSessionSystem.instance().operations().synchronize(
            io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload.inactive(0)));
         ClientSessionSystem.instance().operations().onClientTick();
         var preview = io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload.active(
            1L, false, true, List.of(A), BlockPos.ZERO, BlockPos.ZERO, mode, 0, -1, 0,
            io.github.fastformer.fastplace.selection.OperationMode.MOVE,
            io.github.fastformer.fastplace.selection.OperationStageMode.TRANSFORM,
            BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, net.minecraft.world.phys.Vec3.ZERO, false, false, false);
         assertTrue(ClientSessionSystem.instance().operations().synchronize(preview));
         assertTrue(ClientSessionSystem.instance().operations().remoteSelectionPointing());
         assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
         assertFalse(ClientSessionSystem.instance().operations().selectionSessionActive());
         assertFalse(ClientSessionSystem.instance().operations().selectionDraftActive());
         assertTrue(ClientSessionSystem.instance().operations().undo());
         var restored = ClientSessionSystem.instance().operations().selectionDraft();
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
         assertFalse(ClientSessionSystem.instance().operations().selectionSessionActive());
         assertFalse(ClientSessionSystem.instance().operations().selectionDraftActive());
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
      ClientSessionSystem.instance().operations().selectAllWorkspaceParts();
      ClientSessionSystem.instance().operations().deselectAllWorkspaceParts();
      assertTrue(workspace().selectedIds().isEmpty());
      assertEquals(0, workspace().activeId());
      assertEquals(List.of(first, second), workspace().parts());
      assertTrue(ClientSessionSystem.instance().operations().undo());
      assertEquals(java.util.Set.of(first.id(), second.id()), workspace().selectedIds());
   }
   @Test void qExitsWhenItPopsTheLastSelectionEntry() {
      ClientSessionSystem.instance().operations().enterLocalSelectionSession();
      ClientSessionSystem.instance().operations().handleCreateClick(1, new BlockPos(1, 2, 3));
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertFalse(ClientSessionSystem.instance().operations().selectionSessionActive());
      assertFalse(ClientSessionSystem.instance().operations().active());
      assertFalse(ClientSessionSystem.instance().operations().selectionDraftActive());
      assertTrue(ClientSessionSystem.instance().operations().undo());
      assertTrue(ClientSessionSystem.instance().operations().selectionSessionActive());
      assertEquals(new BlockPos(1, 2, 3), ClientSessionSystem.instance().operations().selectionDraft().firstPoint());
   }
   private static final BlockPos A = new BlockPos(1, 2, 3);
   private static final BlockPos B = new BlockPos(4, 5, 6);

   @Test void entrySetsPointOneAndNextRightCompletesTheCuboid() {
      assertTrue(FastPlaceClientInput.captureSelectionEntry(A, false, 1));
      drain();
      assertTrue(workspace().isEmpty());
      assertEquals(A, ClientSessionSystem.instance().operations().selectionDraft().firstPoint());
      assertNull(ClientSessionSystem.instance().operations().selectionDraft().secondPoint());
      post(1, B);
      drain();
      var part = workspace().latestPart().orElseThrow();
      long identity = workspace().interactionId(part.id());
      assertEquals(A, part.selection().point1());
      assertEquals(B, part.selection().point2());
      assertTrue(part.canAdjustGeometry());
      post(0, null);
      drain();
      assertTrue(workspace().isEmpty());
      assertEquals(A, ClientSessionSystem.instance().operations().selectionDraft().firstPoint());
      assertTrue(ClientSessionSystem.instance().operations().undo());
      assertEquals(identity, workspace().interactionId(part.id()));
   }


   @BeforeEach void reset() {
      ClientSessionSystem.instance().operations().onDisconnected();
      FastPlaceClientInput.inputSession().reset();
   }

   @AfterEach void clear() { reset(); }

   @Test void rightAndMiddleFillThePointsInOrder() {
      for (int firstButton : List.of(1, 2)) {
         for (int completingButton : List.of(1, 2)) {
            reset();
            assertTrue(FastPlaceClientInput.captureInitialSelection(firstButton, A, false, false, 1));
            drain();
            var draft = ClientSessionSystem.instance().operations().selectionDraft();
            assertEquals(A, draft.firstPoint());
            assertNull(draft.secondPoint());
            post(completingButton, B);
            drain();
            var part = workspace().latestPart().orElseThrow();
            assertEquals(A, part.selection().point1());
            assertEquals(B, part.selection().point2());
            assertTrue(part.canAdjustGeometry());
            assertFalse(ClientSessionSystem.instance().operations().selectionDraftActive());
         }
      }
   }

   @Test void leftReopensTheLatestCuboidThenRemovesItsFirstPoint() {
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, A));
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, B));
      assertEquals(B, workspace().latestPart().orElseThrow().selection().point2());
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(0, null));
      assertTrue(workspace().isEmpty());
      assertEquals(A, ClientSessionSystem.instance().operations().selectionDraft().firstPoint());
      assertNull(ClientSessionSystem.instance().operations().selectionDraft().secondPoint());
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(0, null));
      assertFalse(ClientSessionSystem.instance().operations().selectionDraftActive());
      assertFalse(ClientSessionSystem.instance().operations().handleCreateClick(0, null));
      assertTrue(ClientSessionSystem.instance().operations().undo());
      assertEquals(A, ClientSessionSystem.instance().operations().selectionDraft().firstPoint());
      assertTrue(ClientSessionSystem.instance().operations().undo());
      assertEquals(B, workspace().latestPart().orElseThrow().selection().point2());
   }

   @Test void cancellingNewestRevealsTheUnchangedAdjustablePreviousSelection() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(workspace().parts().stream().allMatch(ClientSelectionPart::canAdjustGeometry));
      ClientSessionSystem.instance().operations().selectWorkspacePart(first.id(), false);
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertTrue(workspace().part(second.id()).isEmpty());
      assertEquals(first, workspace().latestPart().orElseThrow());
      assertEquals(first.id(), workspace().activeId());
      assertFalse(ClientSessionSystem.instance().operations().canStartSelectionDraft());
   }

   @Test void pendingPointCancellationLeavesEveryExistingSelectionUntouched() {
      addSelection(0);
      addSelection(10);
      var before = workspace().draftState();
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, new BlockPos(30, 0, 0)));
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertEquals(before, workspace().draftState());
      assertFalse(ClientSessionSystem.instance().operations().selectionDraftActive());
      assertTrue(ClientSessionSystem.instance().operations().undo());
      assertFalse(ClientSessionSystem.instance().operations().selectionDraft().secondPointOnly());
      assertEquals(before, workspace().draftState());
   }

   @Test void fixingNewestDoesNotFixOlderSelectionsOrAllowMiddleToSkipTheTop() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(ClientSessionSystem.instance().operations().fixActiveSelection());
      assertFalse(workspace().part(second.id()).orElseThrow().canAdjustGeometry());
      assertEquals(first, workspace().part(first.id()).orElseThrow());
      assertTrue(ClientSessionSystem.instance().operations().canStartSelectionDraft());
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertFalse(ClientSessionSystem.instance().operations().canStartSelectionDraft());
      assertEquals(first, workspace().latestPart().orElseThrow());
   }

   @Test void enterCompletesPreviewAndSubmitsAllInOnePress() {
      addSelection(0);
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, B));
      assertTrue(ClientSessionSystem.instance().operations().confirmSelection(null, A));
      assertFalse(ClientSessionSystem.instance().operations().selectionDraftActive());
      assertTrue(workspace().isEmpty());
      assertFalse(ClientSessionSystem.instance().operations().undo());
   }

   @Test void newCuboidPointFixesSmartTailAndUndoRestoresTheActiveGroup() {
      var smart = smartPart();
      workspace().addParts(List.of(smart));
      var before = workspace().draftState();
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, B));
      assertFalse(workspace().parts().getFirst().smartEditable());
      assertTrue(ClientSessionSystem.instance().operations().undo());
      assertEquals(before, workspace().draftState());
      assertTrue(workspace().parts().getFirst().smartEditable());
   }

   @Test void scrollMovementFixesSmartGroupAndUndoRestoresIt() {
      workspace().addParts(List.of(smartPart()));
      var before = workspace().draftState();
      assertTrue(ClientSessionSystem.instance().operations().transforms().moveSelected(BlockPos.ZERO.east()));
      assertFalse(workspace().parts().getFirst().smartEditable());
      assertTrue(workspace().parts().getFirst().transformed());
      assertTrue(ClientSessionSystem.instance().operations().undo());
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
      assertTrue(ClientSessionSystem.instance().operations().fixActiveSelection());
      assertTrue(ClientSessionSystem.instance().operations().confirmSelection(null, null));
      assertTrue(workspace().isEmpty());
      assertTrue(workspace().selectedIds().isEmpty());
      assertFalse(ClientSessionSystem.instance().operations().undo());
      assertTrue(workspace().isEmpty());
   }

   @Test void controlEnterCompletesTheDraftAndSubmitsEveryGroup() {
      addSelection(0);
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, A));
      assertTrue(ClientSessionSystem.instance().operations().confirmSelection(null, B));
      assertTrue(workspace().isEmpty());
      assertFalse(ClientSessionSystem.instance().operations().selectionDraftActive());
      assertFalse(ClientSessionSystem.instance().operations().undo());
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
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertEquals(List.of(second), workspace().parts());
      assertTrue(ClientSessionSystem.instance().operations().undo());
      assertEquals(third, workspace().latestPart().orElseThrow());
      workspace().restoreDraftState(stored);
      assertEquals(third, workspace().latestPart().orElseThrow());
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertEquals(second.id(), workspace().activeId());
   }

   @Test void cancellingDuringAnOpenAdjustmentReleasesTheEditAndRevealsPreviousSelection() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(workspace().beginEdit());
      workspace().updatePart(second.withTranslation(new BlockPos(4, 0, 0)));
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertFalse(workspace().editing());
      assertEquals(List.of(first), workspace().parts());
      assertEquals(first.id(), workspace().activeId());
   }

   @Test void completedAndIncompleteSelectionsShareTheSameStackTop() {
      var first = addSelection(0);
      assertEquals(new ClientSelectionStack.Complete(first), workspace().selections().peek().orElseThrow());
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, A));
      var pending = assertInstanceOf(ClientSelectionStack.Pending.class, workspace().selections().peek().orElseThrow());
      assertEquals(A, pending.points().firstPoint());
      assertNull(pending.points().secondPoint());
      assertTrue(workspace().selections().topPart().isEmpty());
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(2, B));
      var complete = assertInstanceOf(ClientSelectionStack.Complete.class, workspace().selections().peek().orElseThrow());
      assertEquals(A, complete.part().selection().point1());
      assertEquals(B, complete.part().selection().point2());
      assertEquals(2, workspace().size());
      assertEquals(first, workspace().parts().getFirst());
   }

   @Test void consecutivePopsAndUndoRestoreEachEntryWithItsPointRole() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, A));
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertEquals(second, workspace().selections().topPart().orElseThrow());
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertEquals(first, workspace().selections().topPart().orElseThrow());
      assertTrue(ClientSessionSystem.instance().operations().undo());
      assertEquals(second, workspace().selections().topPart().orElseThrow());
      assertTrue(ClientSessionSystem.instance().operations().undo());
      var pending = assertInstanceOf(ClientSelectionStack.Pending.class, workspace().selections().peek().orElseThrow());
      assertEquals(A, pending.points().firstPoint());
      assertNull(pending.points().secondPoint());
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(2, B));
      assertEquals(3, workspace().size());
      assertEquals(A, workspace().latestPart().orElseThrow().selection().point1());
      assertEquals(B, workspace().latestPart().orElseThrow().selection().point2());
   }

   @Test void editingAnOlderPartDoesNotMoveItAboveThePendingStackEntry() {
      var first = addSelection(0);
      var second = addSelection(10);
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, A));
      var pending = workspace().selections().peek().orElseThrow();
      assertTrue(workspace().beginEdit());
      workspace().updatePart(first.fixed());
      assertTrue(workspace().finishEdit());
      assertSame(pending, workspace().selections().peek().orElseThrow());
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertEquals(second, workspace().selections().topPart().orElseThrow());
      assertFalse(workspace().part(first.id()).orElseThrow().canAdjustGeometry());
   }

   @Test void poppingAnEmptyStackKeepsTheSessionAvailableForEitherPoint() {
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, A));
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertTrue(workspace().selections().peek().isEmpty());
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, B));
      assertEquals(B, ClientSessionSystem.instance().operations().selectionDraft().firstPoint());
   }

   @Test void middleTargetsTheTopEvenWhenAnOlderSelectionIsActive() {
      var first = addSelection(0);
      var second = addSelection(10);
      workspace().selectOnly(first.id());
      var owner = ClientSessionSystem.instance().operations().interactionScene().owner();
      var middle = new SelectionPointPress(owner, second.id(), workspace().interactionId(second.id()), 2, false, A, 1);
      assertTrue(middle.matches(owner, workspace()));
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, A));
      assertFalse(middle.matches(owner, workspace()));
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      assertTrue(middle.matches(owner, workspace()));
      addSelection(20);
      assertFalse(middle.matches(owner, workspace()));
   }

   @Test void delayedMiddleCannotModifyANewSelectionWithAReusedDisplayNumber() {
      addSelection(0);
      var second = addSelection(10);
      var owner = ClientSessionSystem.instance().operations().interactionScene().owner();
      var middle = new SelectionPointPress(owner, second.id(), workspace().interactionId(second.id()), 2, false, A, 1);
      assertTrue(ClientSessionSystem.instance().operations().cancelLastSelection());
      var replacement = addSelection(20);
      assertEquals(second.id(), replacement.id());
      assertFalse(middle.matches(owner, workspace()));
   }

   private static ClientSelectionPart addSelection(int x) {
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, new BlockPos(x, 0, 0)));
      assertTrue(ClientSessionSystem.instance().operations().handleCreateClick(1, new BlockPos(x + 2, 2, 2)));
      return workspace().latestPart().orElseThrow();
   }

   private static ClientOperationWorkspace workspace() { return ClientSessionSystem.instance().operations().workspace(); }

   private static void post(int button, BlockPos point) {
      FastPlaceClientInput.inputSession().captureSelectionPress(new SelectionDraftPress(
         ClientSessionSystem.instance().operations().interactionScene().owner(), OperationSelectionMode.CUBOID, button,
         point, false, false, 300_000_001L));
   }

   private static void drain() {
      var input = FastPlaceClientInput.inputSession();
      input.drainPhysicalEvents(() -> true, key -> fail(), scroll -> fail(),
         event -> SelectionInputDispatcher.dispatch(null, input, event));
   }
}
