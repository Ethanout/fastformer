package io.github.fastformer.server.task;

/** One task advance; waiting never discards the writer or its recovery resources. */
public enum TaskStep {
   ACTIVE(false, false),
   WAITING(false, false),
   WORLD_UNLOADED(false, false),
   GENERATING(false, false),
   VALIDATING(false, false),
   PREPARING_JOURNAL(false, false),
   FINALIZING(false, false),
   COMMITTING(false, false),
   COMPLETE(true, false),
   EMPTY(true, false),
   CONSTRAINTS_FAILED(true, false),
   EXCEEDED(true, true),
   MEMORY_UNSAFE(true, true),
   FAILED(true, true),
   JOURNAL_FAILED(true, true),
   BLOCKED(true, true),
   CANCELLED(true, true);

   private final boolean terminal;
   private final boolean recoveryRequired;

   TaskStep(boolean terminal, boolean recoveryRequired) {
      this.terminal = terminal;
      this.recoveryRequired = recoveryRequired;
   }

   public boolean terminal() { return terminal; }
   public boolean recoveryRequired() { return recoveryRequired; }
}
