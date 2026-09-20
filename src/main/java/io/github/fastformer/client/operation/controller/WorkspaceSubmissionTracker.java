package io.github.fastformer.client.operation.controller;

import io.github.fastformer.client.session.OperationDraftIdentity;
import io.github.fastformer.fastplace.FastPlaceActivity;
import java.util.Objects;
import java.util.UUID;

/** Tracks the client-side lifetime of one workspace submission. */
final class WorkspaceSubmissionTracker {
   enum Phase {
      IDLE,
      WAITING_FOR_TASK,
      TASK_ACTIVE,
      WAITING_FOR_RESULT
   }

   private final int taskStartTimeoutTicks;
   private final int resultGraceTicks;
   private Phase phase = Phase.IDLE;
   private UUID transferId;
   private UUID ownerId;
   private OperationDraftIdentity identity;
   private int waitTicks;

   WorkspaceSubmissionTracker(int taskStartTimeoutTicks, int resultGraceTicks) {
      if (taskStartTimeoutTicks <= 0 || resultGraceTicks <= 0) {
         throw new IllegalArgumentException("Workspace submission timeouts must be positive");
      }
      this.taskStartTimeoutTicks = taskStartTimeoutTicks;
      this.resultGraceTicks = resultGraceTicks;
   }

   void begin(UUID transferId) {
      begin(transferId, null, null);
   }

   /**
    * Starts one submission and keeps the selection identity that was live at the send.
    *
    * <p>The server preview becomes inactive as soon as the task is queued, so the
    * identity is unavailable later. A disconnect must use the copy held here.</p>
    */
   void begin(UUID transferId, OperationDraftIdentity identity) {
      begin(transferId, identity, null);
   }

   void begin(UUID transferId, OperationDraftIdentity identity, UUID ownerId) {
      this.transferId = Objects.requireNonNull(transferId, "transferId");
      this.identity = identity;
      this.ownerId = ownerId;
      this.phase = Phase.WAITING_FOR_TASK;
      this.waitTicks = 0;
   }

   /** The selection identity at the send, or null when the workspace had no server selection. */
   OperationDraftIdentity identity() {
      return this.identity;
   }

   void observeActivity(FastPlaceActivity activity) {
      if (!pending()) {
         return;
      }
      if (activity == FastPlaceActivity.OPERATION_TASK) {
         phase = Phase.TASK_ACTIVE;
         waitTicks = 0;
      } else if (phase == Phase.TASK_ACTIVE) {
         phase = Phase.WAITING_FOR_RESULT;
         waitTicks = 0;
      }
   }

   boolean tick() {
      if (phase == Phase.TASK_ACTIVE || phase == Phase.IDLE) {
         return false;
      }
      waitTicks++;
      int timeoutTicks = phase == Phase.WAITING_FOR_TASK ? taskStartTimeoutTicks : resultGraceTicks;
      return waitTicks >= timeoutTicks;
   }

   boolean accepts(UUID receivedTransferId) {
      return pending() && transferId.equals(receivedTransferId);
   }

   boolean accepts(UUID receivedTransferId, UUID ownerId) {
      return accepts(receivedTransferId) && Objects.equals(this.ownerId, ownerId);
   }

   boolean pending() {
      return phase != Phase.IDLE;
   }

   boolean pending(UUID ownerId) {
      return pending() && Objects.equals(this.ownerId, ownerId);
   }

   UUID transferId() {
      return transferId;
   }

   UUID ownerId() {
      return ownerId;
   }

   Phase phase() {
      return phase;
   }

   void clear() {
      phase = Phase.IDLE;
      transferId = null;
      ownerId = null;
      identity = null;
      waitTicks = 0;
   }
}
