package io.github.fastformer.fastplace.task;

/** Result of atomically detaching a world-writing task from its owner. */
public enum TaskCancellationResult {
   NOT_ACTIVE,
   CANCELLED_BEFORE_WRITE,
   ROLLBACK_STARTED,
   RECOVERY_BLOCKED;

   public boolean handled() {
      return this != NOT_ACTIVE;
   }

   public boolean recoveryCreated() {
      return this == ROLLBACK_STARTED;
   }
}
