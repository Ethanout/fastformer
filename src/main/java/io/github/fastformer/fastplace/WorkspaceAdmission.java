package io.github.fastformer.fastplace;

import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;

/**
 * What the server did with one workspace admission.
 *
 * <p>A boolean cannot describe this answer. "No task was queued" covers three different
 * situations: a new task did queue, the ledger already holds this transfer, and the
 * admission is refused. Only the last one is a failure. A caller that flattens them
 * answers a replay with a failure packet, and the ledger then records a failure for work
 * that already applied.</p>
 *
 * <p>The admission handler is the only sender of a result packet. This type decides
 * whether a packet goes out, and what it reports.</p>
 *
 * @param kind     what happened to this admission
 * @param recorded the state that the ledger already holds, for {@link Kind#REPLAYED} only
 */
public record WorkspaceAdmission(Kind kind, OperationSubmissionOutcome recorded) {
   /** What happened to one admission. */
   public enum Kind {
      /** A new task entered the queue. The task sends its own result later. */
      QUEUED,
      /** The ledger already holds a state for this transfer. No new task exists. */
      REPLAYED,
      /** The admission is refused, and the ledger holds no state for this transfer. */
      REJECTED
   }

   public WorkspaceAdmission {
      if (kind == null) {
         throw new IllegalArgumentException("An admission needs a kind");
      }
      if (kind == Kind.REPLAYED && recorded == null) {
         throw new IllegalArgumentException("A replayed admission needs the recorded state");
      }
      if (kind != Kind.REPLAYED && recorded != null) {
         throw new IllegalArgumentException("Only a replayed admission carries a recorded state");
      }
   }

   public static WorkspaceAdmission newQueued() {
      return new WorkspaceAdmission(Kind.QUEUED, null);
   }

   public static WorkspaceAdmission replayed(OperationSubmissionOutcome recorded) {
      return new WorkspaceAdmission(Kind.REPLAYED, recorded);
   }

   public static WorkspaceAdmission rejected() {
      return new WorkspaceAdmission(Kind.REJECTED, null);
   }

   /** True when a new task entered the queue. */
   public boolean isQueued() {
      return this.kind == Kind.QUEUED;
   }

   /**
    * The state that the delivered packet reports, or null when no packet goes out.
    *
    * <p>A refused admission reports a retryable failure, because no record exists and no
    * work started. A replay reports the recorded state. A replay of work that still runs
    * reports nothing, because the client would read a packet as a finished result and the
    * server holds no finished result yet.</p>
    */
   public OperationSubmissionOutcome deliveredOutcome() {
      if (this.kind == Kind.REJECTED) {
         return OperationSubmissionOutcome.FAILED_RETRYABLE;
      }
      if (this.kind == Kind.REPLAYED && this.recorded != null && this.recorded.deliverable()) {
         return this.recorded;
      }
      return null;
   }

   /** True when the server must send exactly one result packet for this admission. */
   public boolean sendsResult() {
      return deliveredOutcome() != null;
   }

   /**
    * True when a live task still owns the request scope of this transfer.
    *
    * <p>The scope map is keyed by owner and transfer, so the request that queued a task and
    * a later replay of the same transfer share one entry. A queued task removes that entry
    * when it reports its result. A replay that removed it would take the scope away from
    * the running task, and the task's result packet would then carry the scope of the
    * replay instead of the scope of the request that queued it.</p>
    *
    * <p>A replay of work that still runs therefore keeps the entry. Only an admission that
    * closes the request, meaning a delivered result or a refusal, clears it.</p>
    */
   public boolean keepsRequestScope() {
      if (this.kind == Kind.QUEUED) {
         return true;
      }
      return this.kind == Kind.REPLAYED && this.recorded != null && this.recorded.open();
   }

   /** True when the delivered packet reports a result that the player may send again. */
   public boolean retryable() {
      return this.deliveredOutcome() == OperationSubmissionOutcome.FAILED_RETRYABLE;
   }
}
