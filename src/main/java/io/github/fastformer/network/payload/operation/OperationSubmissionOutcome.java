package io.github.fastformer.network.payload.operation;

/**
 * State of one workspace submission, as the client records it and the server reports it.
 *
 * <p>One type covers both sides on purpose. The receipt file, the query payload, and the
 * answer payload must agree on the meaning of every state.</p>
 */
public enum OperationSubmissionOutcome {
   /**
    * The client wrote the receipt and sent the submission. No answer arrived yet.
    * This state is client-side only. The server never reports it.
    */
   IN_FLIGHT,
   /**
    * The server holds the task as queued or running. The result is still open, so the
    * client must never show this state as success or as failure.
    */
   IN_PROGRESS,
   /** The server reports the world write as applied. */
   APPLIED,
   /** The server reports a failure that the player can retry. */
   FAILED_RETRYABLE,
   /**
    * The server reports a failure that the player cannot retry. The world may hold part
    * of the work, so this state is not the same as a retryable failure.
    */
   FAILED_NONRETRYABLE,
   /**
    * The server moved the work to its recovery path. The journal owns the outcome, and
    * only recovery can settle it. A later submission must not repeat the same work.
    */
   RECOVERY_REQUIRED,
   /**
    * The server holds no record of this transfer. The outcome is unknown, not failed.
    * A receipt in this state keeps the draft and never resubmits on its own.
    */
   UNKNOWN;

   /** True while the submission may still finish, so the client must not conclude. */
   public boolean open() {
      return this == IN_FLIGHT || this == IN_PROGRESS;
   }

   /** True when the draft can be dropped because the world write already happened. */
   public boolean applied() {
      return this == APPLIED;
   }

   /** True when the player may send the same work again. */
   public boolean retryable() {
      return this == FAILED_RETRYABLE;
   }

   /** True when a failure reached a final state that a retry cannot improve. */
   public boolean terminalFailure() {
      return this == FAILED_NONRETRYABLE || this == RECOVERY_REQUIRED;
   }

   /** True when the server may send this state as an answer to a query. */
   public boolean reportable() {
      return this != IN_FLIGHT;
   }

   /**
    * True when the server may deliver this state as a finished result packet.
    *
    * <p>{@code IN_PROGRESS} is reportable but not deliverable. A result packet means that
    * the work settled, and a client that reads such a packet for running work would treat
    * it as a failure and send the same work again.</p>
    */
   public boolean deliverable() {
      return this == APPLIED
         || this == FAILED_RETRYABLE
         || this == FAILED_NONRETRYABLE
         || this == RECOVERY_REQUIRED;
   }
}
