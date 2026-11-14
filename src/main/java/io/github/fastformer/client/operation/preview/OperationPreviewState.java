package io.github.fastformer.client.operation.preview;

import io.github.fastformer.client.session.reconnect.ReconnectSnapshotWindow;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;

/** Owns one scope's preview revisions and explicit reconnect confirmation. */
public final class OperationPreviewState {
   private final ReconnectSnapshotWindow reconnect = new ReconnectSnapshotWindow();
   private OperationPreviewPayload pending;
   private OperationPreviewPayload live = OperationPreviewPayload.inactive();
   private long revision = -1;

   public record Update(boolean accepted, boolean discardStoredDraft, boolean previousActive) {
      private static final Update IGNORED = new Update(false, false, false);
   }

   public OperationPreviewPayload live() { return live; }
   public boolean restorePending() { return pending != null; }

   /** Ends the local remote-selection view without accepting a new server revision. */
   public void clearSelection(OperationSelectionMode mode) {
      live = OperationPreviewPayload.inactive(revision, mode);
   }

   public Update receive(OperationPreviewPayload payload, boolean storedDraftPending) {
      if (payload == null || payload.operationRevision() <= revision
         || pending != null && payload.operationRevision() == pending.operationRevision()) {
         return Update.IGNORED;
      }
      boolean discard = false;
      if (!reconnect.armed() && pending == null && storedDraftPending) {
         if (payload.active()) return hold(payload);
         discard = true;
      }
      if (reconnect.armed()) {
         reconnect.observeSnapshot();
         if (payload.active()) {
            reconnect.clear();
            return hold(payload);
         }
         discard = true;
      } else if (pending != null && payload.operationRevision() != pending.operationRevision()) {
         pending = null;
      }
      boolean previousActive = live.active();
      revision = payload.operationRevision();
      live = payload;
      return new Update(true, discard, previousActive);
   }

   private Update hold(OperationPreviewPayload payload) {
      pending = payload;
      live = OperationPreviewPayload.inactive();
      return Update.IGNORED;
   }

   /** Consumes the confirmation candidate without accepting its revision yet. */
   public OperationPreviewPayload takePending() {
      var value = pending;
      pending = null;
      return value;
   }

   public void leaveEnvironment() {
      reconnect.arm();
      pending = null;
      live = OperationPreviewPayload.inactive();
      revision = -1;
   }

   /** Silence ends the wait without discarding any draft. */
   public boolean tick() {
      return reconnect.tick() == ReconnectSnapshotWindow.Result.SNAPSHOT_RECEIVED;
   }
}
