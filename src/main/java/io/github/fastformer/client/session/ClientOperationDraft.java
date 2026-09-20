package io.github.fastformer.client.session;

import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable operation draft data. Transient input and submission state is excluded.
 *
 * @param submissionId the submission that this draft was written for, or null when the
 *                     draft did not leave the client. Only the draft that a specific
 *                     submission wrote may be cleared when that submission applies. A
 *                     draft with no submission id belongs to the player and must stay.
 */
public record ClientOperationDraft(
   int version,
   OperationDraftIdentity identity,
   ClientOperationWorkspace.DraftState workspace,
   ClientSelectionSession.DraftState selection,
   OperationSubmissionOrigin origin,
   UUID submissionId
) {
   public static final int CURRENT_VERSION = 1;

   public ClientOperationDraft {
      if (version != CURRENT_VERSION) {
         throw new IllegalArgumentException("Unsupported client operation draft version");
      }
      Objects.requireNonNull(workspace, "workspace");
      Objects.requireNonNull(selection, "selection");
      // A local-only draft has no server selection by definition, so an identity on it
      // would be a contradiction. A server-confirmed draft normally carries the identity
      // that a restore must match. It may carry none when the selection went away before
      // the send. Such a draft is kept for its content, and no restore can confirm it.
      if (origin == OperationSubmissionOrigin.LOCAL_ONLY && identity != null) {
         throw new IllegalArgumentException("A local-only draft carries no server identity");
      }
   }

   public ClientOperationDraft(
      OperationDraftIdentity identity,
      ClientOperationWorkspace.DraftState workspace,
      ClientSelectionSession.DraftState selection
   ) {
      this(CURRENT_VERSION, identity, workspace, selection, null, null);
   }

   public ClientOperationDraft(
      OperationDraftIdentity identity,
      ClientOperationWorkspace.DraftState workspace,
      ClientSelectionSession.DraftState selection,
      OperationSubmissionOrigin origin
   ) {
      this(CURRENT_VERSION, identity, workspace, selection, origin, null);
   }

   /**
    * True when the server selection identity must match before this draft returns.
    *
    * <p>A draft with no recorded origin comes from an older client version or from a
    * connection that never submitted. Both cases carry a server identity, so both
    * require the match. Only an explicit local-only origin skips it.</p>
    */
   public boolean requiresServerIdentity() {
      return this.origin != OperationSubmissionOrigin.LOCAL_ONLY;
   }

   /** True when this draft is the one that a given submission wrote. */
   public boolean belongsToSubmission(UUID transferId) {
      return transferId != null && transferId.equals(this.submissionId);
   }
}
