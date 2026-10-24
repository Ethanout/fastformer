package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.input.SelectionScrollAction;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionScrollMailboxTest {
   private ClientInputSession input;

   @BeforeEach
   void prepare() {
      ClientOperationController.clearWorkspace();
      input = FastPlaceClientInput.inputSession();
      input.reset();
      addPart();
      input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
   }

   @AfterEach
   void clear() {
      input.reset();
      ClientOperationController.clearWorkspace();
   }

   @Test
   void consecutiveMovesKeepCapturedAxisAndExecuteOnlyWhenDrained() {
      var snapshot = capture();
      input.postScroll(snapshot);
      input.postScroll(snapshot);
      assertEquals(Vec3.ZERO, translation());
      drain();
      assertEquals(new Vec3(2, 0, 0), translation());
      assertTrue(ClientOperationController.undo());
      assertEquals(Vec3.ZERO, translation());
   }

   @Test
   void queueDelayDoesNotMergeScrollsThatWerePhysicallyFarApart() {
      var captured = capture().selectionAction();
      var first = new SelectionScrollAction(captured.owner(), captured.targets(), captured.gesture(), 1_000_000_000L);
      var second = new SelectionScrollAction(captured.owner(), captured.targets(), captured.gesture(), 1_250_000_001L);
      input.postScroll(new ScrollInputSnapshot(1, first));
      input.postScroll(new ScrollInputSnapshot(1, second));
      drain();
      assertEquals(new Vec3(2, 0, 0), translation());
      assertTrue(ClientOperationController.undo());
      assertEquals(new Vec3(1, 0, 0), translation());
   }

   @Test
   void deletedPartWithReusedNumberCannotReceiveOldMove() {
      var old = capture();
      assertTrue(ClientOperationController.workspace().removeSelectedParts());
      addPart();
      input.postScroll(old);
      drain();
      assertEquals(Vec3.ZERO, translation());
   }

   @Test
   void ownerReplacementRejectsOldMove() {
      var old = capture();
      ClientOperationController.clearWorkspace();
      addPart();
      input.postScroll(old);
      drain();
      assertEquals(Vec3.ZERO, translation());
   }

   @Test
   void earlierQueuedSubmissionBlocksMove() {
      input.postKeyboard(new KeyboardInputSnapshot(257, 0, 1, 0, 1L, false, false));
      input.postScroll(capture());
      input.drainPhysicalEvents(() -> true, key -> assertTrue(input.routing.submit(42L)),
         scroll -> ScrollInputDispatcher.dispatch(null, input, scroll));
      assertEquals(Vec3.ZERO, translation());
   }

   @Test
   void cancellingQueueDoesNotMoveParts() {
      input.postScroll(capture());
      input.discardPhysicalEvents();
      drain();
      assertEquals(Vec3.ZERO, translation());
   }

   @Test
   void stackGestureIsCapturedAndConsecutiveScrollsUndoTogether() {
      var stack = captureStack(1);
      input.postScroll(stack);
      input.postScroll(stack);
      assertEquals(BlockPos.ZERO, transform().repeats().max());
      drain();
      assertEquals(new BlockPos(2, 0, 0), transform().repeats().max());
      assertEquals(new BlockPos(3, 0, 0), transform().repeatStride());
      assertEquals(new Vec3(1, 1, 1), transform().scale());
      assertTrue(ClientOperationController.undo());
      assertEquals(BlockPos.ZERO, transform().repeats().max());
   }

   @Test
   void stackAndMoveDoNotShareUndoGroup() {
      input.postScroll(captureStack(1));
      input.postScroll(capture());
      drain();
      assertEquals(new Vec3(1, 0, 0), translation());
      assertEquals(new BlockPos(1, 0, 0), transform().repeats().max());
      assertTrue(ClientOperationController.undo());
      assertEquals(Vec3.ZERO, translation());
      assertEquals(new BlockPos(1, 0, 0), transform().repeats().max());
      assertTrue(ClientOperationController.undo());
      assertEquals(BlockPos.ZERO, transform().repeats().max());
   }

   @Test
   void deletedSelectionRejectsQueuedStackAndInverseScrollRetractsCopy() {
      var stack = captureStack(1);
      assertTrue(ClientOperationController.workspace().removeSelectedParts());
      addPart();
      input.postScroll(stack);
      drain();
      assertEquals(BlockPos.ZERO, transform().repeats().max());
      input.postScroll(captureStack(1));
      input.postScroll(captureStack(-1));
      drain();
      assertEquals(BlockPos.ZERO, transform().repeats().max());
      assertTrue(ClientOperationController.undo());
      assertEquals(new BlockPos(1, 0, 0), transform().repeats().max());
   }

   private ScrollInputSnapshot captureStack(int direction) {
      var action = SelectionScrollAction.capture(ClientOperationController.interactionScene().owner(),
         ClientOperationController.workspace(), new SelectionScrollAction.Stack(
            io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.X, 1, direction)).orElseThrow();
      return new ScrollInputSnapshot(direction, action);
   }

   @Test
   void scrollingDownPastZeroStacksOnNegativeYAndUndoRestoresPositiveY() {
      var axis = io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.Y;
      var workspace = ClientOperationController.workspace();
      var up = SelectionScrollAction.capture(ClientOperationController.interactionScene().owner(), workspace,
         new SelectionScrollAction.Stack(axis, 1, 1)).orElseThrow();
      input.postScroll(new ScrollInputSnapshot(1, up));
      drain();
      assertEquals(new BlockPos(0, 1, 0), transform().repeats().max());
      var down = SelectionScrollAction.capture(ClientOperationController.interactionScene().owner(), workspace,
         new SelectionScrollAction.Stack(axis, 1, -1)).orElseThrow();
      input.postScroll(new ScrollInputSnapshot(-1, down));
      input.postScroll(new ScrollInputSnapshot(-1, down));
      drain();
      assertEquals(new BlockPos(0, -1, 0), transform().repeats().min());
      assertEquals(BlockPos.ZERO, transform().repeats().max());
      assertEquals(new BlockPos(0, 3, 0), transform().repeatStride());
      assertTrue(ClientOperationController.undo());
      assertEquals(BlockPos.ZERO, transform().repeats().min());
      assertEquals(new BlockPos(0, 1, 0), transform().repeats().max());
   }

   @Test
   void rotatedSelectionStacksAlongCapturedLocalAxisAndKeepsItsStride() {
      var workspace = ClientOperationController.workspace();
      var part = workspace.selectedParts().iterator().next();
      assertTrue(workspace.beginEdit());
      workspace.updatePart(part.withTransform(part.transform().withRotation(new Vec3(0, Math.PI / 2, 0))));
      workspace.finishEdit();
      var stack = SelectionScrollAction.stackAlongView(transform(), new Vec3(0, 0, -1), 1);
      assertEquals(io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.X, stack.axis());
      assertEquals(1, stack.side());
      var action = SelectionScrollAction.capture(ClientOperationController.interactionScene().owner(), workspace, stack).orElseThrow();
      input.postScroll(new ScrollInputSnapshot(1, action));
      input.postScroll(new ScrollInputSnapshot(1, action));
      drain();
      assertEquals(new BlockPos(2, 0, 0), transform().repeats().max());
      assertEquals(new BlockPos(3, 0, 0), transform().repeatStride());
      assertEquals(new Vec3(0, Math.PI / 2, 0), transform().rotation());
      assertEquals(new Vec3(1, 1, 1), transform().scale());
   }

   @Test
   void multipleSelectionsUseExistingCommonStackUnit() {
      var bounds = OperationSelectionVolume.cuboid(new BlockPos(10, 0, 0), new BlockPos(12, 2, 2), BlockPos.ZERO, BlockPos.ZERO);
      var part = new ClientSelectionPart(2, ClientSelectionPart.Source.WORLD, bounds, Map.of(), WorkspaceTransform.IDENTITY, false);
      ClientOperationController.workspace().addParts(List.of(part));
      ClientOperationController.selectAllWorkspaceParts();
      input.postScroll(captureStack(1));
      input.postScroll(captureStack(1));
      drain();
      for (var selected : ClientOperationController.workspace().selectedParts()) {
         assertEquals(new BlockPos(2, 0, 0), selected.transform().repeats().max());
         assertEquals(new BlockPos(13, 0, 0), selected.transform().repeatStride());
         assertEquals(new Vec3(1, 1, 1), selected.transform().scale());
      }
      assertTrue(ClientOperationController.undo());
      for (var selected : ClientOperationController.workspace().selectedParts()) assertEquals(BlockPos.ZERO, selected.transform().repeats().max());
   }

   private static WorkspaceTransform transform() {
      return ClientOperationController.workspace().selectedParts().iterator().next().transform();
   }

   private ScrollInputSnapshot capture() {
      var move = SelectionScrollAction.capture(ClientOperationController.interactionScene().owner(),
         ClientOperationController.workspace(), new SelectionScrollAction.Move(new BlockPos(1, 0, 0))).orElseThrow();
      return new ScrollInputSnapshot(1, move);
   }

   private void drain() {
      input.drainPhysicalEvents(() -> true, key -> fail(),
         scroll -> ScrollInputDispatcher.dispatch(null, input, scroll));
   }

   private static Vec3 translation() {
      return ClientOperationController.workspace().selectedParts().iterator().next().transform().translation();
   }

   private static void addPart() {
      var bounds = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO);
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, bounds, Map.of(), WorkspaceTransform.IDENTITY, false);
      ClientOperationController.workspace().addParts(List.of(part));
      ClientOperationController.selectAllWorkspaceParts();
   }
}
