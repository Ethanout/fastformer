package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionDraftMailboxTest {
   private ClientInputSession input;
   private ClientSelectionSession selection;

   @BeforeEach
   void prepare() throws ReflectiveOperationException {
      ClientOperationController.clearWorkspace();
      this.input = FastPlaceClientInput.inputSession();
      this.input.reset();
      this.input.routing.observe(ClientInputStateMachine.State.SELECTING);
      var method = ClientOperationController.class.getDeclaredMethod("selectionSession");
      method.setAccessible(true);
      this.selection = (ClientSelectionSession) method.invoke(null);
      this.selection.setSelectionMode(OperationSelectionMode.CUBOID);
   }

   @AfterEach
   void clear() {
      this.input.reset();
      io.github.fastformer.client.operation.selection.SelectionToolPreference.set(OperationSelectionMode.CUBOID);
      ClientOperationController.clearWorkspace();
   }

   @Test
   void idleToolCycleRemembersSmartWithoutTakingOwnershipUntilSessionEntry() {
      io.github.fastformer.client.operation.selection.SelectionToolPreference.set(OperationSelectionMode.CUBOID);
      ClientOperationController.cycleIdleSelectionTool();
      assertEquals(OperationSelectionMode.SMART, this.selection.selectionMode());
      assertEquals(OperationSelectionMode.SMART,
         io.github.fastformer.client.operation.selection.SelectionToolPreference.get());
      assertFalse(ClientOperationController.selectionSessionActive());
      assertFalse(ClientOperationController.canStartSelectionDraft());
      ClientOperationController.enterLocalSelectionSession();
      assertTrue(ClientOperationController.canStartSelectionDraft());
      assertEquals(OperationSelectionMode.SMART, this.selection.selectionMode());
      assertTrue(ClientOperationController.cancelLastSelection());
      assertFalse(ClientOperationController.selectionSessionActive());
      assertFalse(ClientOperationController.canStartSelectionDraft());
   }

   @Test
   void twoOrdinaryClicksCompleteInOrderAndUndoRestoresTheFirstPoint() {
      var first = new BlockPos(2, 3, 4);
      postClick(0, first, false);
      postClick(1, new BlockPos(4, 5, 6), false);
      assertFalse(this.selection.hasDraft());
      drain();
      assertEquals(1, this.selection.workspace().size());
      assertFalse(this.selection.hasDraft());
      assertTrue(this.selection.workspace().parts().getFirst().canAdjustGeometry());
      assertTrue(ClientOperationController.undo());
      assertTrue(this.selection.workspace().isEmpty());
      assertEquals(List.of(first), this.selection.draftPoints());
      assertFalse(this.input.blocksDraftLoad());
   }

   @Test
   void enterFixLeavesRoomForEveryMouseButtonToStartTheNextDraft() {
      for (int button : List.of(0, 1, 2)) {
         ClientOperationController.clearWorkspace();
         this.selection.setSelectionMode(OperationSelectionMode.CUBOID);
         ClientOperationController.handleCreateClick(0, BlockPos.ZERO);
         ClientOperationController.handleCreateClick(1, new BlockPos(2, 2, 2));
         assertTrue(ClientOperationController.fixActiveSelection());
         assertTrue(ClientOperationController.canStartSelectionDraft());
         assertFalse(this.selection.workspace().parts().getFirst().canAdjustGeometry());
         assertTrue(ClientOperationController.handleCreateClick(button, new BlockPos(5, 5, 5)));
         assertEquals(List.of(new BlockPos(5, 5, 5)), this.selection.draftPoints());
      }
   }

   @Test
   void undoingExplicitFixRestoresTheEditableSelection() {
      ClientOperationController.handleCreateClick(0, BlockPos.ZERO);
      ClientOperationController.handleCreateClick(1, new BlockPos(2, 2, 2));
      assertTrue(ClientOperationController.fixActiveSelection());
      assertTrue(ClientOperationController.undo());
      assertTrue(this.selection.workspace().parts().getFirst().canAdjustGeometry());
      assertFalse(ClientOperationController.canStartSelectionDraft());
   }

   @Test
   void modeSwitchKeepsExistingCuboidAndUndoRestoresOnlyTheCreationTool() {
      ClientOperationController.handleCreateClick(0, BlockPos.ZERO);
      ClientOperationController.handleCreateClick(1, new BlockPos(2, 2, 2));
      var original = this.selection.workspace().parts().getFirst();
      assertTrue(ClientOperationController.cycleDraftMode());
      assertEquals(OperationSelectionMode.SMART, this.selection.selectionMode());
      assertEquals(List.of(original), this.selection.workspace().parts());
      assertEquals(0, this.selection.draftSize());
      assertTrue(ClientOperationController.undo());
      assertEquals(OperationSelectionMode.CUBOID, this.selection.selectionMode());
      assertFalse(this.selection.hasDraft());
      assertEquals(original, this.selection.workspace().parts().getFirst());
   }

   @Test
   void altMiddleAlsoCompletesTheTwoPointSelection() {
      postClick(2, BlockPos.ZERO, true);
      postClick(2, new BlockPos(2, 2, 2), true);
      drain();
      assertFalse(this.selection.hasDraft());
      var part = this.selection.workspace().latestPart().orElseThrow();
      assertEquals(BlockPos.ZERO, part.selection().point1());
      assertEquals(new BlockPos(2, 2, 2), part.selection().point2());
      assertTrue(part.canAdjustGeometry());
      assertFalse(this.input.blocksDraftLoad());
   }

   @Test
   void immutableTargetSurvivesMouseMovementBeforeDispatch() {
      var target = new BlockPos.MutableBlockPos(1, 2, 3);
      var press = this.input.captureSelectionPress(snapshot(0, target, false));
      target.set(9, 9, 9);
      release(0);
      drain();
      assertEquals(new BlockPos(1, 2, 3), this.selection.draftFirst());
      assertEquals(1234, press.snapshot().occurredAtNanos());
      assertTrue(press.snapshot().control());
   }

   @Test
   void modeReplacementRejectsQueuedPointsWithoutChangingTheNewDraft() {
      postClick(0, BlockPos.ZERO, false);
      this.selection.setSelectionMode(OperationSelectionMode.PRISM);
      var before = this.selection.draftState();
      drain();
      assertEquals(before, this.selection.draftState());
      assertFalse(this.input.blocksDraftLoad());
   }

   @Test
   void sessionReplacementRejectsAnOldPointEvenWhenItsModeMatches() {
      postClick(0, BlockPos.ZERO, false);
      ClientOperationController.clearWorkspace();
      var before = this.selection.draftState();
      drain();
      assertEquals(before, this.selection.draftState());
      assertFalse(this.input.blocksDraftLoad());
   }

   @Test
   void earlierSubmissionRejectsThePointAndSettlesItsPhysicalCapture() {
      this.input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      this.input.postKeyboard(new KeyboardInputSnapshot(257, 1, 1, 0, 100, false, false));
      postClick(0, BlockPos.ZERO, false);
      this.input.drainPhysicalEvents(() -> true, key -> assertTrue(this.input.routing.submit(42)),
         scroll -> fail(), SelectionDraftMailboxTest::dispatch);
      assertFalse(this.selection.hasDraft());
      assertFalse(this.input.selectionPointer.active());
   }

   @Test
   void earlierCancelDiscardsThePointAndItsRelease() {
      this.input.postKeyboard(new KeyboardInputSnapshot(81, 1, 1, 0, 100, false, false));
      postClick(0, BlockPos.ZERO, false);
      this.input.drainPhysicalEvents(() -> true, key -> assertTrue(this.input.cancel()),
         scroll -> fail(), event -> fail());
      assertFalse(this.selection.hasDraft());
      assertFalse(this.input.selectionPointer.active());
   }

   @Test
   void lockedWorkspaceRejectsAltWithoutChangingDraftOrModifier() {
      this.input.modifier.press(1, true, true);
      postClick(2, BlockPos.ZERO, true);
      this.selection.workspace().setLocked(true);
      drain();
      assertFalse(this.selection.hasDraft());
      assertFalse(this.selection.altHeld());
      assertFalse(this.input.modifier.consumed());
      assertFalse(this.input.selectionPointer.active());
   }

   @Test
   void altWithoutAHitConsumesTheChordButDoesNotCreateDraftHistory() {
      this.input.modifier.press(1, true, true);
      postClick(0, null, true);
      drain();
      assertTrue(this.selection.altHeld());
      assertTrue(this.input.modifier.consumed());
      assertFalse(this.selection.hasDraft());
      assertEquals(0, this.selection.workspace().undoSize());
   }

   private SelectionDraftPress snapshot(int button, BlockPos point, boolean alt) {
      return new SelectionDraftPress(this.selection.interactionOwnerId(), this.selection.selectionMode(),
         button, point, alt, true, 1234);
   }

   private void postClick(int button, BlockPos point, boolean alt) {
      this.input.captureSelectionPress(snapshot(button, point, alt));
      release(button);
   }

   private void release(int button) {
      this.input.postSelectionPointer(this.input.selectionPointer.release(button, 2000));
   }

   private void drain() {
      this.input.drainPhysicalEvents(() -> true, key -> fail(), scroll -> fail(), SelectionDraftMailboxTest::dispatch);
   }

   private static void dispatch(SelectionPointerEvent event) {
      SelectionInputDispatcher.dispatch(null, FastPlaceClientInput.inputSession(), event);
   }
}
