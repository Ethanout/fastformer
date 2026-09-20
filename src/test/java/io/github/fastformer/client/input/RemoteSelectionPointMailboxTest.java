package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import java.util.ArrayList;
import java.util.List;
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

   @Test void requestsShareTheKeyboardQueueAndSendOnlyOnce() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      input.postKeyboard(new KeyboardInputSnapshot(257, 1, 1, 0, 10, false, false));
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.SECOND);
      input.drainPhysicalEvents(() -> true, key -> assertEquals(List.of(OperationPointPayload.Role.FIRST), sent),
         scroll -> fail(), pointer -> fail(), this::send);
      drain(true);
      assertEquals(List.of(OperationPointPayload.Role.FIRST, OperationPointPayload.Role.SECOND), sent);
      assertFalse(input.canCancelPendingRemotePoint());
   }

   @Test void cancelBeforeInitialRequestDoesNotNeedAServerPreview() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      assertTrue(input.canCancelPendingRemotePoint());
      assertTrue(input.cancel());
      drain(true);
      assertTrue(sent.isEmpty());
      assertFalse(input.canCancelPendingRemotePoint());
   }

   @Test void contextLossDiscardsRequestsAndPendingOwnership() {
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.SECOND);
      drain(false);
      assertTrue(sent.isEmpty());
      assertFalse(input.canCancelPendingRemotePoint());
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
      assertFalse(input.canCancelPendingRemotePoint());
   }

   @Test void cancellationDuringBatchDiscardsRequestsStillInThatBatch() {
      input.postKeyboard(new KeyboardInputSnapshot(81, 1, 1, 0, 10, false, false));
      FastPlaceClientInput.queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
      input.drainPhysicalEvents(() -> true, key -> assertTrue(input.cancel()),
         scroll -> fail(), pointer -> fail(), this::send);
      assertTrue(sent.isEmpty());
      assertFalse(input.canCancelPendingRemotePoint());
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
