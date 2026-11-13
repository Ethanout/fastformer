package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.task.TaskStep;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Placement phase ordering; queue ownership, history publication and feedback live outside it. */
final class PlacementTaskSteps {
   private PlacementTaskSteps() {}

   static TaskStep prepare(PlacementTask task) {
      if (!task.prepare()) return task.waitingForGenerationMemory() ? TaskStep.WAITING : TaskStep.GENERATING;
      if (task.generationConstraintsFailed()) return TaskStep.CONSTRAINTS_FAILED;
      if (task.failed()) return TaskStep.FAILED;
      if (task.exceededLimit()) return TaskStep.EXCEEDED;
      if (task.memoryUnsafe()) return TaskStep.MEMORY_UNSAFE;
      return task.total() == 0 ? TaskStep.EMPTY : TaskStep.ACTIVE;
   }

   static TaskStep advance(PlacementTask task, WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      if (!task.validateSnapshots(level, budget)) return TaskStep.VALIDATING;
      if (task.memoryUnsafe()) return TaskStep.MEMORY_UNSAFE;
      if (task.failed()) return TaskStep.FAILED;
      JournalPreparation journal = task.prepareJournal(context);
      if (journal == JournalPreparation.PENDING) return TaskStep.PREPARING_JOURNAL;
      if (journal == JournalPreparation.FAILED) return TaskStep.JOURNAL_FAILED;

      while (context.writesAllowed() && task.blocks().hasNext()
         && (budget.tryConsume() || (!task.hasWrites() && budget.tryConsumeFirstWrite()))) {
         BlockPos position = task.blocks().next();
         task.consumed();
         task.place(context, level, position);
         if (task.failed()) break;
      }
      if (!context.writesAllowed()) return TaskStep.BLOCKED;
      if (task.failed()) return TaskStep.FAILED;
      if (task.readyToFinalize()) return finalizePlacement(task, level, budget);
      return task.snapshotsComplete() ? TaskStep.ACTIVE : TaskStep.VALIDATING;
   }

   private static TaskStep finalizePlacement(PlacementTask task, ServerLevel level, WorldTaskBudget budget) {
      if (!task.hasWrites()) return TaskStep.COMPLETE;
      task.releaseGenerationState();
      task.resizeMemoryReservationForTransaction();
      if (!task.finalizeSnapshots(level, budget)) return TaskStep.FINALIZING;
      if (task.failed()) return TaskStep.FAILED;
      return switch (task.prepareCommit()) {
         case PENDING -> TaskStep.COMMITTING;
         case FAILED -> TaskStep.FAILED;
         case READY -> TaskStep.COMPLETE;
      };
   }
}
