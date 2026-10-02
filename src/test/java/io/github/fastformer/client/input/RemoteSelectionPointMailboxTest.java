package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.fastplace.selection.OperationMode;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationStageMode;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteSelectionPointMailboxTest {
   private ClientInputSession input;
   private final List<OperationPointPayload.Role> sent = new ArrayList<>();

   @BeforeEach void prepare() {
      ClientOperationController.onDisconnected();
      input = FastPlaceClientInput.inputSession();
      input.reset();
   }
   @AfterEach void clear() { input.reset(); ClientOperationController.onDisconnected(); }

   @Test void initialEmptyHandLeftThenRightCompletesTheSameSelection() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      drain(true);
      var first = new BlockPos(2, 3, 4);
      var second = new BlockPos(5, 6, 7);
      receiveFirstPoint(first);

      assertTrue(FastPlaceClientInput.queueSelectionDraft(null,
         new OperationInteractionIntent.CreateSelection(second), 1, false, 100));
      drain(true);

      assertEquals(List.of(OperationPointPayload.Role.FIRST, OperationPointPayload.Role.SECOND), sent);
      assertFalse(ClientOperationController.selectionDraftActive());
      assertTrue(ClientOperationController.synchronize(preview(3, List.of(first, second))));
      var parts = ClientOperationController.workspace().parts();
      assertEquals(1, parts.size());
      assertEquals(first, parts.getFirst().selection().point1());
      assertEquals(second, parts.getFirst().selection().point2());
      assertTrue(parts.getFirst().canAdjustGeometry());
   }

   @Test void middleAfterRemoteFirstPointStaysWithTheSameOwner() {
      receiveFirstPoint(BlockPos.ZERO);
      assertTrue(FastPlaceClientInput.queueSelectionDraft(null,
         new OperationInteractionIntent.CreateSelection(new BlockPos(4, 5, 6)), 2, false, 100));
      drain(true);
      assertEquals(List.of(OperationPointPayload.Role.EXTRA), sent);
      assertFalse(ClientOperationController.selectionDraftActive());
   }

   @Test void cancelledRemoteFirstPointDoesNotStealTheNextLocalDraft() {
      receiveFirstPoint(BlockPos.ZERO);
      assertTrue(ClientOperationController.cancelLastSelection());
      assertFalse(ClientOperationController.remoteSelectionPointing());
      var next = new BlockPos(8, 9, 10);
      assertTrue(ClientOperationController.handleCreateClick(1, next));
      assertTrue(sent.isEmpty());
      assertEquals(List.of(next), ClientOperationController.selectionDraft().points());
   }

   private void receiveFirstPoint(BlockPos first) {
      assertTrue(ClientOperationController.synchronize(OperationPreviewPayload.inactive(1)));
      ClientOperationController.onClientTick();
      assertTrue(ClientOperationController.synchronize(preview(2, List.of(first))));
      input.routing.observe(ClientInputStateMachine.State.SELECTING);
      assertTrue(ClientOperationController.remoteSelectionPointing());
   }

   private static OperationPreviewPayload preview(long revision, List<BlockPos> points) {
      return OperationPreviewPayload.active(revision, true, points.size() > 1, points,
         BlockPos.ZERO, BlockPos.ZERO, OperationSelectionMode.CUBOID, 0, -1, 0,
         OperationMode.MOVE, OperationStageMode.TRANSFORM, BlockPos.ZERO,
         BlockPos.ZERO, BlockPos.ZERO, Vec3.ZERO, false, false, false);
   }

   @Test void requestsShareTheKeyboardQueueAndSendOnlyOnce() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      input.postKeyboard(new KeyboardInputSnapshot(257, 1, 1, 0, 10, false, false));
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.SECOND);
      input.drainPhysicalEvents(() -> true, key -> assertEquals(List.of(OperationPointPayload.Role.FIRST), sent),
         scroll -> fail(), pointer -> fail(), this::send);
      drain(true);
      assertEquals(List.of(OperationPointPayload.Role.FIRST, OperationPointPayload.Role.SECOND), sent);
      assertFalse(input.canCancelPendingSessionStart());
   }

   @Test void cancelBeforeInitialRequestDoesNotNeedAServerPreview() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      assertTrue(input.canCancelPendingSessionStart());
      assertTrue(input.cancel());
      drain(true);
      assertTrue(sent.isEmpty());
      assertFalse(input.canCancelPendingSessionStart());
   }

   @Test void contextLossDiscardsRequestsAndPendingOwnership() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.SECOND);
      drain(false);
      assertTrue(sent.isEmpty());
      assertFalse(input.canCancelPendingSessionStart());
      assertFalse(input.blocksDraftLoad());
   }

   @Test void replacedSelectionOwnerRejectsOldRequests() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      ClientOperationController.clearWorkspace();
      drain(true);
      assertTrue(sent.isEmpty());
   }

   @Test void differentSessionCannotReceiveAQueuedSelectionRequest() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      input.routing.observe(ClientInputStateMachine.State.BUILDING);
      drain(true);
      assertTrue(sent.isEmpty());
   }

   @Test void resetDiscardsPendingRequests() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      input.reset();
      drain(true);
      assertTrue(sent.isEmpty());
      assertFalse(input.canCancelPendingSessionStart());
   }

   @Test void cancellationDuringBatchDiscardsRequestsStillInThatBatch() {
      input.postKeyboard(new KeyboardInputSnapshot(81, 1, 1, 0, 10, false, false));
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      input.drainPhysicalEvents(() -> true, key -> assertTrue(input.cancel()),
         scroll -> fail(), pointer -> fail(), this::send);
      assertTrue(sent.isEmpty());
      assertFalse(input.canCancelPendingSessionStart());
      assertFalse(input.blocksDraftLoad());
   }

   @Test void precedingSubmissionRejectsTheRequest() {
      input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      input.postKeyboard(new KeyboardInputSnapshot(257, 1, 1, 0, 10, false, false));
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.SECOND);
      input.drainPhysicalEvents(() -> true, key -> assertTrue(input.routing.submit(42L)),
         scroll -> fail(), pointer -> fail(), this::send);
      assertTrue(sent.isEmpty());
   }

   @Test void localDraftPreventsLateRemoteSelectionCreation() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      ClientOperationController.handleCreateClick(0, net.minecraft.core.BlockPos.ZERO);
      assertTrue(ClientOperationController.selectionDraftActive());
      drain(true);
      assertTrue(sent.isEmpty());
   }

   private void send(RemoteSelectionPointRequest request) {
      FastPlaceClientInput.handleRemoteSelectionPoint(request, payload -> sent.add(payload.role()));
   }
   private void drain(boolean active) {
      input.drainPhysicalEvents(() -> active, key -> fail(), scroll -> fail(), pointer -> fail(), this::send);
   }
}
