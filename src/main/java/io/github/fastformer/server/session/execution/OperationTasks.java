package io.github.fastformer.server.session.execution;

import com.mojang.logging.LogUtils;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.submission.WorkspaceResultSink;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.block.SubmittedWorkspaceTask;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Owns selection/workspace writers, recovery transfer and their completion receipts. */
public final class OperationTasks extends TaskExecution<WorldTask> {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final Function<WorldTaskContext, WorkspaceResultSink> results;

   public OperationTasks(BooleanSupplier closed, Predicate<UUID> placementsBusy,
      Function<WorldTaskContext, WorkspaceResultSink> results) {
      super(closed, placementsBusy);
      this.results = Objects.requireNonNull(results);
   }

   /** Schedules the first resume of one queued task. A scheduling fault keeps the task. */
   public void scheduleResume(ServerPlayer player, WorldTask task) {
      try {
         WorldTaskContext context = new WorldTaskContext(player.getServer(), player.getUUID());
         HistoryService history = HistorySystem.of(player);
         schedule(context, task, history);
      } catch (RuntimeException exception) {
         LOGGER.warn("FastFormer queued a workspace submission but could not schedule its resume", exception);
      }
   }

   @Override void onCancellationAccepted(WorldTaskContext context, WorldTask task, TaskCancellationResult result) {
      if (task instanceof SubmittedWorkspaceTask workspace) {
         results.apply(context).finish(workspace.completion(context.owner(), false,
            result == TaskCancellationResult.CANCELLED_BEFORE_WRITE,
            result == TaskCancellationResult.ROLLBACK_STARTED), context.onlinePlayer());
      }
   }

   @Override
   public void service(WorldTaskContext context, HistoryService history) {
      WorldTask task = queue.get(context.owner());
      if (task == null) return;
      try {
         Optional<TaskStep> advanced = queue.tick(context.withResume(() -> resume(context, task, history)), task);
         if (advanced.isEmpty()) return;
         TaskStep step = advanced.orElseThrow();
         if (step.terminal()) finish(context, task, step, history);
         else reportProgress(context, task, step);
      } catch (RuntimeException | OutOfMemoryError exception) {
         recoverFailure(context, task, history, exception);
      }
   }

   private void finish(WorldTaskContext context, WorldTask task, TaskStep step, HistoryService history) {
      boolean retryableWorkspaceFailure = !task.hasWrites();
      boolean failureTransferred = step.recoveryRequired();
      boolean recoveryCreated = false;
      if (failureTransferred) {
         TaskCancellationResult handover = queue.transfer(context, task, history::acceptTransferredTask);
         if (!handoverAccepted(handover)) return;
         recoveryCreated = handover.recoveryCreated();
      } else if (task.hasWrites()) {
         JournalPreparation publication = history.pollPreparedOperation(context, task.preparedBatch(), task.journal());
         if (publication == JournalPreparation.PENDING) {
            context.actionBar(PlayerMessages.text("fastformer.message.history_saving"));
            return;
         }
         if (publication == JournalPreparation.FAILED) {
            step = TaskStep.FAILED;
            failureTransferred = true;
            TaskCancellationResult handover = queue.transfer(context, task, history::acceptTransferredTask);
            if (!handoverAccepted(handover)) return;
            recoveryCreated = handover.recoveryCreated();
         } else {
            task.markComplete();
            task.releaseCommittedTransactionState();
            task.releaseLease(context);
         }
      } else {
         // A no-op may own a prepared journal: cleanup must settle before lease reuse.
         task.releaseAfterCancelledJournal(context);
      }
      if (task instanceof SubmittedWorkspaceTask workspace) {
         results.apply(context).finish(workspace.completion(context.owner(),
            step == TaskStep.COMPLETE || step == TaskStep.EMPTY,
            retryableWorkspaceFailure, recoveryCreated), context.onlinePlayer());
      }
      if (step == TaskStep.COMPLETE) task.markComplete();
      LOGGER.info("FastFormer operation {} finished with result {}: {}", task.operationId(), step, task.metricsSummary());
      if (!failureTransferred) task.releaseMemoryReservation();
      queue.remove(context.owner(), task);
      context.actionBar(completionStatus(task, step, recoveryCreated));
   }

   private void recoverFailure(WorldTaskContext context, WorldTask task, HistoryService history, Throwable failure) {
      boolean retryableWorkspaceFailure = !task.hasWrites();
      TaskCancellationResult handover = queue.transfer(context, task, history::acceptTransferredTask);
      LOGGER.error("FastFormer operation task failed for {} (handover={})", context.owner(), handover, failure);
      if (!handoverAccepted(handover)) return;
      boolean recoveryCreated = handover.recoveryCreated();
      queue.remove(context.owner(), task);
      if (task instanceof SubmittedWorkspaceTask workspace) {
         results.apply(context).finish(workspace.completion(context.owner(),
            false, retryableWorkspaceFailure, recoveryCreated), context.onlinePlayer());
      }
      LOGGER.error("FastFormer operation metrics: {}", task.metricsSummary());
      context.actionBar(operationFailureStatus(task, recoveryCreated));
   }

   private void reportProgress(WorldTaskContext context, WorldTask task, TaskStep step) {
      context.actionBar(switch (step) {
         case WAITING -> PlayerMessages.text("fastformer.message.world_write_waiting");
         case WORLD_UNLOADED -> PlayerMessages.text("fastformer.message.history_dimension_failed");
         default -> PlayerMessages.text("fastformer.message.operation_phase", task.phaseName());
      });
   }

   private MutableComponent completionStatus(WorldTask task, TaskStep step, boolean recoveryCreated) {
      return switch (step) {
         case COMPLETE -> PlayerMessages.text("fastformer.message.operation_complete");
         case EMPTY -> PlayerMessages.text("fastformer.message.operation_empty");
         case FAILED -> operationFailureStatus(task, recoveryCreated);
         case JOURNAL_FAILED -> PlayerMessages.text("fastformer.message.recovery_journal_failed");
         case MEMORY_UNSAFE -> PlayerMessages.text("fastformer.message.operation_memory_unsafe");
         default -> PlayerMessages.text("fastformer.message.operation_too_large_run");
      };
   }

   private MutableComponent operationFailureStatus(
      WorldTask task, boolean recoveryCreated
   ) {
      String key = recoveryCreated
         ? "fastformer.message.operation_failed_rollback"
         : "fastformer.message.operation_failed_no_recovery";
      return PlayerMessages.text(key, task.phaseName(), task.operationId(), task.metricsSummary());
   }

   /**
    * True when the running task of one owner is the given workspace transfer.
    *
    * <p>The owner alone is not enough. A player can start a new task while an older
    * transfer is still recorded, so a query must match the transfer id.</p>
    */
   public boolean transferActive(UUID owner, UUID transferId) {
      if (owner == null || transferId == null) {
         return false;
      }
      WorldTask task = queue.get(owner);
      return task instanceof SubmittedWorkspaceTask workspace
         && workspace.transferId().equals(transferId);
   }

}
