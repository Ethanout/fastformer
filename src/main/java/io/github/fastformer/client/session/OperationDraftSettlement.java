package io.github.fastformer.client.session;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Decides the outcome of clearing the local draft of one applied submission.
 *
 * <p>The staged draft and the durable draft file are two owners. Each owner is examined
 * on its own, so a match on one owner never removes the copy of the other owner.</p>
 *
 * <p>Every question has three answers, because two are not enough. A missing copy is
 * confirmed work. A copy that belongs to another submission is none of this
 * submission's business, which is also confirmed work. A copy that cannot be read, or
 * that a delete could not remove, is unconfirmed. The caller then keeps the cleanup
 * pending and tries again at the next boundary.</p>
 *
 * <p>The two owners use different inputs, so a test can force every answer without a
 * file system.</p>
 */
public final class OperationDraftSettlement {
   /** The answer for one owner. */
   public enum Ownership {
      /** No copy of this submission exists here. Nothing to remove. */
      ABSENT,
      /** The copy of this submission existed and left. */
      REMOVED,
      /** The copy could not be read, or a delete failed. Keep the cleanup pending. */
      UNCONFIRMED
   }

   /** The result for the two owners of one submission. */
   public record Result(Ownership staged, Ownership durable) {
      /** True when both owners confirmed that no copy of this submission remains. */
      public boolean confirmed() {
         return this.staged != Ownership.UNCONFIRMED && this.durable != Ownership.UNCONFIRMED;
      }
   }

   /**
    * The result of settling one applied submission.
    *
    * @param drafts      what the two owners answered
    * @param recordSaved true when the durable receipt holds the applied result and no
    *                    pending cleanup. A false value means the client cannot promise
    *                    that a later start knows about this cleanup.
    */
   public record Applied(Result drafts, boolean recordSaved) {
      /** True when the client owes nothing further for this submission. */
      public boolean confirmed() {
         return this.drafts.confirmed() && this.recordSaved;
      }
   }

   /** How the durable draft file answered the ownership question. */
   public record DurableProbe(State state, UUID submissionId) {
      public enum State {
         /** No draft file exists. */
         ABSENT,
         /** A draft file exists and its submission id was read. */
         READ,
         /** A draft file exists and its content could not be read. */
         UNREADABLE
      }

      public static DurableProbe absent() {
         return new DurableProbe(State.ABSENT, null);
      }

      public static DurableProbe read(UUID submissionId) {
         return new DurableProbe(State.READ, submissionId);
      }

      public static DurableProbe unreadable() {
         return new DurableProbe(State.UNREADABLE, null);
      }
   }

   private OperationDraftSettlement() {
   }

   /**
    * Resolves the staged owner.
    *
    * @param transferId       the applied submission
    * @param stagedSubmissionId the submission that the staged draft names, or null when
    *                         no draft is staged
    * @param clearStaged      removes the staged draft from memory
    */
   public static Ownership resolveStaged(
      UUID transferId, UUID stagedSubmissionId, Runnable clearStaged
   ) {
      if (transferId == null || stagedSubmissionId == null) {
         // Nothing is staged, or the staged draft belongs to no submission. Either way
         // this submission owns no staged copy.
         return Ownership.ABSENT;
      }
      if (!transferId.equals(stagedSubmissionId)) {
         // The staged draft belongs to a newer submission of the player. It stays.
         return Ownership.ABSENT;
      }
      clearStaged.run();
      // Clearing memory cannot fail, so this owner is always confirmed.
      return Ownership.REMOVED;
   }

   /**
    * Resolves the durable owner.
    *
    * @param transferId the applied submission
    * @param probe      what the draft file reported
    * @param remove     deletes the draft file and returns true when the file is gone
    */
   public static Ownership resolveDurable(
      UUID transferId, DurableProbe probe, BooleanSupplier remove
   ) {
      if (transferId == null || probe == null) {
         return Ownership.UNCONFIRMED;
      }
      if (probe.state() == DurableProbe.State.ABSENT) {
         return Ownership.ABSENT;
      }
      if (probe.state() == DurableProbe.State.UNREADABLE) {
         // The client cannot prove which submission owns this file. A delete could destroy
         // the draft of another submission, so the answer is unconfirmed.
         return Ownership.UNCONFIRMED;
      }
      if (probe.submissionId() == null) {
         // A draft from an older client names no submission. Ownership is unknown.
         return Ownership.UNCONFIRMED;
      }
      if (!transferId.equals(probe.submissionId())) {
         // The file belongs to a newer submission of the player. It stays.
         return Ownership.ABSENT;
      }
      return remove.getAsBoolean() ? Ownership.REMOVED : Ownership.UNCONFIRMED;
   }

   /** Combines the two owner answers. */
   public static Result combine(Ownership staged, Ownership durable) {
      return new Result(staged, durable);
   }

   /** The probe answer for a draft file that names no submission. */
   public static DurableProbe unattributed() {
      return DurableProbe.read(null);
   }
}
