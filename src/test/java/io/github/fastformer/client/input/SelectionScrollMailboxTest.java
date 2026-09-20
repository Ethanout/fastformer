package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
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

   private ScrollInputSnapshot capture() {
      var move = SelectionScrollMove.capture(ClientOperationController.interactionScene().owner(),
         ClientOperationController.workspace(), new BlockPos(1, 0, 0)).orElseThrow();
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
