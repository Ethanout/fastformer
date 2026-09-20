package io.github.fastformer.client.session;

import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Durable record of one workspace submission.
 *
 * <p>The receipt survives the connection that sent it. It answers three questions
 * after a reconnect: which submission waits, whether a server selection identity can
 * confirm it, and what the client last knew about its result.</p>
 *
 * <p>The scope of the receipt is the file that holds it. That file is named from the
 * connection, the dimension, and the player, so a receipt never crosses a connection
 * or a dimension.</p>
 *
 * @param transferId       identity of the submission
 * @param origin           whether a server selection identity confirmed this submission
 * @param identity         server selection identity at the send, or null when none was live
 * @param dimension        dimension that received the submission
 * @param outcome          last known state
 * @param recordedAtMillis wall clock of the last state change, for the ledger and for logs
 * @param cleanupPending   true when the applied world write has not yet cleared its local
 *                         draft copy. The client keeps this flag on disk, because the
 *                         server ledger can restart and then answer {@code UNKNOWN}. The
 *                         known result must survive that answer.
 */
public record OperationSubmissionReceipt(
   UUID transferId,
   OperationSubmissionOrigin origin,
   OperationDraftIdentity identity,
   ResourceLocation dimension,
   OperationSubmissionOutcome outcome,
   long recordedAtMillis,
   boolean cleanupPending
) {
   public OperationSubmissionReceipt {
      Objects.requireNonNull(transferId, "transferId");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(outcome, "outcome");
      if (origin == OperationSubmissionOrigin.LOCAL_ONLY && identity != null) {
         throw new IllegalArgumentException("A local-only submission carries no server identity");
      }
      if (cleanupPending && !outcome.applied()) {
         throw new IllegalArgumentException("Only an applied submission waits for a draft cleanup");
      }
   }

   /**
    * Builds a receipt in the state that follows from its outcome.
    *
    * <p>An applied outcome needs a draft cleanup. Every other outcome needs none.</p>
    */
   public OperationSubmissionReceipt(
      UUID transferId,
      OperationSubmissionOrigin origin,
      OperationDraftIdentity identity,
      ResourceLocation dimension,
      OperationSubmissionOutcome outcome,
      long recordedAtMillis
   ) {
      this(transferId, origin, identity, dimension, outcome, recordedAtMillis, outcome.applied());
   }

   public OperationSubmissionReceipt withOutcome(OperationSubmissionOutcome next, long nowMillis) {
      return new OperationSubmissionReceipt(
         transferId, origin, identity, dimension, next, nowMillis, next.applied()
      );
   }

   /** Records that the local draft of this submission is gone. */
   public OperationSubmissionReceipt withCleanupConfirmed(long nowMillis) {
      if (!this.cleanupPending) {
         return this;
      }
      return new OperationSubmissionReceipt(
         transferId, origin, identity, dimension, outcome, nowMillis, false
      );
   }

   /** True when the applied world write has not yet cleared its local draft copy. */
   public boolean needsCleanup() {
      return this.cleanupPending;
   }

   /** True when a live server selection must match this receipt before the draft returns. */
   public boolean requiresServerIdentity() {
      return origin == OperationSubmissionOrigin.SERVER_SELECTION;
   }

   /**
    * True when the receipt holds the identity that a restore must match.
    *
    * <p>A server selection can go away before the send, for example when the source
    * selection changed. The receipt still tracks that submission for result
    * reconciliation, but no restore can confirm it.</p>
    */
   public boolean confirmsIdentity() {
      return requiresServerIdentity() && identity != null;
   }
}
