package io.github.fastformer.fastplace;

/** Result of atomically detaching a world-writing task from its owner. */
enum TaskCancellationResult {
   NOT_ACTIVE,
   CANCELLED_BEFORE_WRITE,
   ROLLBACK_STARTED,
   RECOVERY_BLOCKED;

   boolean handled() {
      return this != NOT_ACTIVE;
   }
}
