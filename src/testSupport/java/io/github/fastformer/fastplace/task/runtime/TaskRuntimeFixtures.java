package io.github.fastformer.fastplace.task.runtime;

import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.memory.MemoryAdmission;
import io.github.fastformer.fastplace.world.memory.MemoryAdmissionStatus;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import java.util.Objects;

/** Seeds resources before registering a writer; never replaces a running publication. */
public final class TaskRuntimeFixtures {
   private TaskRuntimeFixtures() {}

   public static boolean hasWorkingSet(LeasedJournaledTask task) {
      return task.memoryReservation != null;
   }

   public static void reserveWorkingSet(LeasedJournaledTask task, long bytes) {
      if (hasWorkingSet(task)) throw new IllegalStateException("Task already owns a working set");
      task.memoryReservation = WorldOperationMemory.reserve(
         new MemoryAdmission(MemoryAdmissionStatus.ALLOWED, bytes, Long.MAX_VALUE)).orElseThrow();
   }

   public static void installCommit(LeasedJournaledTask task, WorldOperationCommit commit) {
      if (task.operationCommit != null) throw new IllegalStateException("Task already owns a publication");
      task.operationCommit = Objects.requireNonNull(commit);
   }
}
