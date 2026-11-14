package io.github.fastformer.server.submission;

import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Bounded record of workspace submission outcomes, owned by one server instance.
 *
 * <p>The client cannot learn the result of a submission while it is away. The server
 * completes the world write offline, and the result packet has no receiver. This ledger
 * keeps the answer until the client asks for it after a reconnect.</p>
 *
 * <p>An entry is keyed by owner, dimension, and transfer id. A dimension in the key
 * prevents an answer for one environment from settling a submission of another. A
 * transfer enters the ledger as {@link OperationSubmissionOutcome#IN_PROGRESS} when the
 * task is accepted, and leaves it as a final state.</p>
 *
 * <p>The ledger of a server instance is dropped when that server stops. A different
 * save in the same address therefore starts with an empty ledger, and every old
 * transfer answers {@code UNKNOWN} instead of a result that belongs to another save.</p>
 *
 * <p>The owning session scope creates and discards this instance. Callers use it only on
 * that server's thread; no lookup or callback creates another ledger.</p>
 */
public final class WorkspaceSubmissionLedger implements Scoped {
   /** Finished transfers kept for one owner. The oldest finished entry leaves first. */
   public static final int MAX_ENTRIES_PER_OWNER = WorkspaceSubmissionBook.MAX_ENTRIES_PER_OWNER;
   /** Finished transfers kept for one server, over all owners. */
   public static final int MAX_ENTRIES_PER_SERVER = WorkspaceSubmissionBook.MAX_ENTRIES_PER_SERVER;
   /** Age at which a finished entry stops answering. A running entry never ages out. */
   public static final long FINISHED_TTL_MILLIS = WorkspaceSubmissionBook.FINISHED_TTL_MILLIS;
   private final WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
   private boolean closed;

   /** One recorded outcome. */
   public record Entry(
      UUID transferId, ResourceLocation dimension, OperationSubmissionOutcome outcome, long recordedAtMillis
   ) {
      public Entry {
         if (transferId == null || dimension == null || outcome == null) {
            throw new IllegalArgumentException("A ledger entry needs a transfer id, a dimension, and an outcome");
         }
      }
   }

   /** Records that a task was accepted and now runs. */
   public void begin(
      UUID owner, ResourceLocation dimension, UUID transferId
   ) {
      record(owner, dimension, transferId, OperationSubmissionOutcome.IN_PROGRESS);
   }

   /**
    * Records the final state of a finished transfer.
    *
    * <p>The returned state is the state that the ledger holds after the call. A caller
    * must deliver that state, not the state that it intended to record, because the ledger
    * may already hold something stronger.</p>
    *
    * @param recoveryCreated true when the server moved the work to its recovery path
    * @return the state that this ledger holds for the transfer, or UNKNOWN after discard
    */
   public OperationSubmissionOutcome finish(
      UUID owner, ResourceLocation dimension, UUID transferId,
      boolean accepted, boolean retryable, boolean recoveryCreated
   ) {
      if (closed) return OperationSubmissionOutcome.UNKNOWN;
      OperationSubmissionOutcome intended = stateFor(accepted, retryable, recoveryCreated);
      OperationSubmissionOutcome stored = record(owner, dimension, transferId, intended);
      return stored == null ? intended : stored;
   }

   /**
    * Maps the three report flags of one finished transfer to one state.
    *
    * <p>Pure, so a test needs no server. A recovery handoff outranks a retryable failure,
    * because the journal owns the outcome and a retry must not repeat the same work.</p>
    */
   public static OperationSubmissionOutcome stateFor(
      boolean accepted, boolean retryable, boolean recoveryCreated
   ) {
      if (accepted) {
         return OperationSubmissionOutcome.APPLIED;
      }
      if (recoveryCreated) {
         return OperationSubmissionOutcome.RECOVERY_REQUIRED;
      }
      return retryable
         ? OperationSubmissionOutcome.FAILED_RETRYABLE
         : OperationSubmissionOutcome.FAILED_NONRETRYABLE;
   }

   /**
    * Answers the state of one transfer.
    *
    * <p>A transfer that still runs keeps its {@code IN_PROGRESS} answer, because only a
    * finished entry can age out. An aged-out or unknown transfer answers {@code UNKNOWN}.</p>
    *
    * @return the recorded state, or {@code UNKNOWN} when this ledger holds no entry
    */
   public OperationSubmissionOutcome outcomeFor(
      UUID owner, ResourceLocation dimension, UUID transferId
   ) {
      Entry entry = find(owner, dimension, transferId);
      return entry == null ? OperationSubmissionOutcome.UNKNOWN : entry.outcome();
   }

   /** Returns the recorded entry, or null when this ledger holds no matching entry. */
   public Entry find(
      UUID owner, ResourceLocation dimension, UUID transferId
   ) {
      return closed ? null : book.find(owner, dimension, transferId);
   }

   /**
    * Drops finished entries that passed their time limit.
    *
    * <p>The world task scheduler calls this each tick. A running entry stays, because a
    * timeout would answer {@code UNKNOWN} for work that the server still performs.</p>
    */
   public void tick() {
      if (!closed) book.expireFinished(System.currentTimeMillis());
   }

   /** Records one state. Returns the state the book holds after the call, or null. */
   private OperationSubmissionOutcome record(
      UUID owner, ResourceLocation dimension, UUID transferId,
      OperationSubmissionOutcome outcome
   ) {
      return closed ? null : book.record(owner, dimension, transferId, outcome, System.currentTimeMillis());
   }

   /** Drops the entries of one owner on one server. */
   public void clearOwner(UUID owner) {
      book.clearOwner(owner);
   }

   /** Discarding a server permanently retires this ledger, including late callbacks. */
   @Override public void onDiscard() {
      closed = true;
      book.clear();
   }
}
