package io.github.fastformer.client.session;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.input.ClientInputSession;
import java.util.Objects;
import java.util.UUID;

/** Player-owned client state that survives LocalPlayer replacement. */
public final class ClientPlayerSession {
   private final UUID playerId;
   private final ClientSelectionSession selectionSession = new ClientSelectionSession();
   private final ClientInputSession inputSession = new ClientInputSession();
   private ClientOperationDraft suspendedOperationDraft;

   public ClientPlayerSession(UUID playerId) {
      this.playerId = Objects.requireNonNull(playerId, "playerId");
   }

   public UUID playerId() {
      return playerId;
   }

   /**
    * Leaves the current environment without discarding a draft already staged for it.
    *
    * <p>A world-session boundary suspends the live draft of the environment that is left
    * behind. That staged draft is the only way back to it, so leaving one environment for
    * another must clear live gesture state only. Confirmation against the server selection
    * identity decides later whether the staged draft is applied or discarded.
    */
   public void detachEnvironment() {
      this.clearLiveInteraction();
   }

   /**
    * Builds the draft of a submission that is about to leave, without changing live state.
    *
    * <p>The caller writes the result to disk. This method must not stage the draft: a
    * staged draft stays in memory after the submission applies, and a later disconnect
    * would then restore content that the server already wrote.</p>
    *
    * @param identity             server selection identity at the send, or null when none is live
    * @param originWhenNoIdentity origin to record when no identity confirms the draft
    * @param transferId           the submission that this draft is written for
    * @return the draft to persist, or null when no durable copy can be built
    */
   public ClientOperationDraft buildSubmittedDraft(
      OperationDraftIdentity identity, OperationSubmissionOrigin originWhenNoIdentity, UUID transferId
   ) {
      if (originWhenNoIdentity == OperationSubmissionOrigin.LOCAL_ONLY) {
         identity = null;
      }
      if (operationWorkspace().isEmpty() && !this.selectionSession.hasDraft()) {
         return null;
      }
      if (identity != null) {
         return new ClientOperationDraft(
            ClientOperationDraft.CURRENT_VERSION,
            identity,
            operationWorkspace().draftState(),
            this.selectionSession.draftState(),
            OperationSubmissionOrigin.SERVER_SELECTION,
            transferId
         );
      }
      if (originWhenNoIdentity == OperationSubmissionOrigin.LOCAL_ONLY) {
         return new ClientOperationDraft(
            ClientOperationDraft.CURRENT_VERSION,
            null,
            operationWorkspace().draftState(),
            this.selectionSession.draftState(),
            OperationSubmissionOrigin.LOCAL_ONLY,
            transferId
         );
      }
      // No identity and no proof that the workspace is client-only. No restore could
      // ever confirm it, so the caller must not send.
      return null;
   }

   /**
    * Saves durable operation data and clears all live gesture state.
    *
    * <p>A workspace that exists only on the client keeps no server identity. An earlier
    * version dropped such a draft here, which lost every client-only paste at a
    * disconnect. The draft now records that it is local-only, so a restore can tell the
    * two cases apart instead of discarding both.</p>
    *
    * @param identity          server selection identity, or null when none is live
    * @param originWhenNoIdentity origin to record when no identity confirms the draft
    */
   public void suspendOperationDraft(OperationDraftIdentity identity) {
      suspendOperationDraft(identity, null, null);
   }

   public void suspendOperationDraft(
      OperationDraftIdentity identity, OperationSubmissionOrigin originWhenNoIdentity
   ) {
      suspendOperationDraft(identity, originWhenNoIdentity, null);
   }

   /**
    * Saves durable operation data and clears all live gesture state.
    *
    * @param submissionId the submission that owns the live workspace, or null when the
    *                     workspace is the player's own work. This value keeps the durable
    *                     copy tied to its submission, so a later result clears exactly
    *                     that copy and no other.
    */
   public void suspendOperationDraft(
      OperationDraftIdentity identity, OperationSubmissionOrigin originWhenNoIdentity, UUID submissionId
   ) {
      operationWorkspace().cancelEdit();
      boolean liveDraft = !operationWorkspace().isEmpty() || this.selectionSession.hasDraft();
      if (!liveDraft) {
         this.clearLiveInteraction();
         return;
      }
      if (identity != null) {
         this.suspendedOperationDraft = new ClientOperationDraft(
            ClientOperationDraft.CURRENT_VERSION,
            identity,
            operationWorkspace().draftState(),
            this.selectionSession.draftState(),
            submissionId == null ? null : OperationSubmissionOrigin.SERVER_SELECTION,
            submissionId
         );
      } else if (originWhenNoIdentity == OperationSubmissionOrigin.LOCAL_ONLY) {
         this.suspendedOperationDraft = new ClientOperationDraft(
            ClientOperationDraft.CURRENT_VERSION,
            null,
            operationWorkspace().draftState(),
            this.selectionSession.draftState(),
            OperationSubmissionOrigin.LOCAL_ONLY,
            submissionId
         );
      } else {
         // No identity and no proof that the workspace is client-only. Keep the durable
         // copy that was already staged for this scope instead of overwriting it.
         this.suspendedOperationDraft = null;
      }
      this.clearLiveInteraction();
   }

   /**
    * Restores a staged draft.
    *
    * <p>A server-confirmed draft returns only when the server still owns the same
    * selection. A local-only draft returns only when its content proves that no server
    * selection is involved, and the draft file scope already isolated the connection,
    * the dimension, and the player. No path skips every check.</p>
    */
   public boolean restoreSuspendedOperationDraft(OperationDraftIdentity identity) {
      ClientOperationDraft suspended = this.suspendedOperationDraft;
      this.suspendedOperationDraft = null;
      if (suspended == null) {
         return false;
      }
      if (suspended.requiresServerIdentity()) {
         if (identity == null || !identity.equals(suspended.identity())) {
            return false;
         }
      } else if (!isLocalOnly(suspended)) {
         // The receipt claimed a client-only workspace, but the stored parts reference
         // world blocks. Such a draft needs a server identity that it does not have.
         return false;
      }
      operationWorkspace().restoreDraftState(suspended.workspace());
      this.selectionSession.restoreDraftState(suspended.selection());
      return true;
   }

   /** True when no part of a stored draft depends on a server-owned world selection. */
   private static boolean isLocalOnly(ClientOperationDraft draft) {
      for (ClientSelectionPart part : draft.workspace().parts()) {
         if (part.source() != ClientSelectionPart.Source.CLIPBOARD) {
            return false;
         }
      }
      return !draft.workspace().parts().isEmpty();
   }

   public boolean suspendedOperationDraft() {
      return this.suspendedOperationDraft != null;
   }

   public void discardSuspendedOperationDraft() {
      this.suspendedOperationDraft = null;
   }

   public ClientOperationDraft suspendedOperationDraftData() {
      return this.suspendedOperationDraft;
   }

   /** Stages a decoded draft. The server identity is checked only on confirmation. */
   public void stageSuspendedOperationDraft(ClientOperationDraft draft) {
      this.suspendedOperationDraft = draft;
      this.clearLiveInteraction();
   }

   private void clearLiveInteraction() {
      inputSession.reset();
      selectionSession.clearLiveInteraction();
   }

   public ClientInputSession inputSession() {
      return this.inputSession;
   }

   public void resetInput() {
      this.inputSession.reset();
      this.selectionSession.clearTransientInteraction();
   }

   /** Compatibility access to the selection session's part container. */
   public ClientOperationWorkspace operationWorkspace() {
      return selectionSession.workspace();
   }

   /** Player-owned selection gesture and focus state. */
   public ClientSelectionSession selectionSession() {
      return selectionSession;
   }

}
