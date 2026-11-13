package io.github.fastformer.fastplace.task.runtime;

import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.WorldTask;
import net.minecraft.server.level.ServerLevel;
import io.github.fastformer.fastplace.world.memory.MemoryAdmission;
import io.github.fastformer.fastplace.world.memory.MemoryAdmissionStatus;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import java.util.Objects;

/** Seeds resources before registering a writer; never replaces a running publication. */
public final class TaskRuntimeFixtures {
   private TaskRuntimeFixtures() {}

   /** Runs the real batch boundary with a deterministic budget, after the fixture's admission. */
   public static TaskStep tickBatch(WorldTask task, WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      if (!(task instanceof LeasedJournaledTask journaled)) throw new IllegalArgumentException("Not a journaled writer");
      return journaled.tickBatch(context, level, budget);
   }

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
