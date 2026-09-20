package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.controller.ClientOperationController;
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
      ClientOperationController.clearWorkspace();
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
      assertTrue(ClientOperationController.undo());
      assertTrue(this.selection.workspace().isEmpty());
      assertEquals(List.of(first), this.selection.draftPoints());
      assertFalse(this.input.blocksDraftLoad());
   }

   @Test
   void altMiddleSequenceKeepsTwoInputPointsAndExpandsTheBounds() {
      postClick(2, BlockPos.ZERO, true);
      postClick(2, new BlockPos(2, 2, 2), true);
      postClick(2, new BlockPos(-3, 4, 1), true);
      drain();
      assertEquals(List.of(BlockPos.ZERO, new BlockPos(2, 2, 2)), this.selection.draftPoints());
      assertEquals(new BlockPos(-3, 0, 0), this.selection.draftMinPoint());
      assertEquals(new BlockPos(2, 4, 2), this.selection.draftMaxPoint());
      assertTrue(this.selection.workspace().isEmpty());
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
