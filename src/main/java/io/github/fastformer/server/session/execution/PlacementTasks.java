package io.github.fastformer.server.session.execution;

import com.mojang.logging.LogUtils;
import io.github.fastformer.common.api.ShapeCommitted;
import io.github.fastformer.server.api.HistoryNotifications;
import io.github.fastformer.common.geometry.generation.source.ProgressiveBlockGeneration;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.task.metrics.WorldOperationPhase;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.block.PlacementTask;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

/** Owns queued placement execution, recovery transfer, completion and delayed resumes. */
public final class PlacementTasks extends TaskExecution<PlacementTask> {
   private static final Logger LOGGER = LogUtils.getLogger();

   public PlacementTasks(BooleanSupplier closed) { super(closed, ignored -> false); }

   @Override
   public void service(WorldTaskContext context, HistoryService history) {
      UUID owner = context.owner();
      PlacementTask task = queue.get(owner);
      if (task == null) return;
      try {
         Optional<TaskStep> advanced = queue.tick(context.withResume(() -> resume(context, task, history)), task);
         if (advanced.isEmpty()) return;
         TaskStep step = advanced.orElseThrow();
         Optional<ShapeCommitted> committed = Optional.empty();
         if (!step.terminal()) {
            reportTaskProgress(context, task, step);
            return;
         }
         if (step.recoveryRequired()) {
            settleFailedTask(context, task, history);
            LOGGER.warn("FastFormer placement {} ended with {}: {}", task.operationId(), step, task.metricsSummary());
            context.actionBar(placementFailureStatus(task, owner, step));
            return;
         }
         if (step == TaskStep.COMPLETE && task.hasWrites()) {
            JournalPreparation historyCommit = history.pollPreparedOperation(context, task.preparedBatch(), task.journal());
            if (historyCommit == JournalPreparation.PENDING) {
               context.actionBar(PlayerMessages.text("fastformer.message.history_saving"));
               return;
            }
            if (historyCommit == JournalPreparation.FAILED) {
               settleFailedTask(context, task, history);
               context.actionBar(failureStatus(task, owner));
               return;
            }
            committed = HistoryNotifications.captureShape(context, task, ShapeCommitted.Kind.PLACEMENT);
            task.releaseLease(context);
         } else {
            task.releaseAfterCancelledJournal(context);
         }
         queue.remove(owner, task);
         task.releaseMemoryReservation();
         task.releaseCommittedTransactionState();
         committed.ifPresent(context.events()::publish);
         if (step == TaskStep.COMPLETE) {
            task.markComplete();
            LOGGER.info("FastFormer placement operation {} finished: {}", task.operationId(), task.metricsSummary());
            context.chat(PlayerMessages.text("fastformer.message.placement_placed", task.placed()));
         } else if (step == TaskStep.CONSTRAINTS_FAILED) {
            context.chat(PlayerMessages.text("fastformer.message.face_generation_constraints_failed"));
         } else {
            context.actionBar(PlayerMessages.text("fastformer.message.operation_empty"));
         }
      } catch (RuntimeException | OutOfMemoryError exception) {
         settleFailedTask(context, task, history);
         LOGGER.error("FastFormer placement task failed for {}: {}", owner, task.metricsSummary(), exception);
         context.actionBar(failureStatus(task, owner));
      }
   }

   private void reportTaskProgress(WorldTaskContext context, PlacementTask task, TaskStep step) {
      switch (step) {
         case WAITING -> context.actionBar(PlayerMessages.text("fastformer.message.world_write_waiting"));
         case WORLD_UNLOADED -> context.actionBar(PlayerMessages.text("fastformer.message.history_dimension_failed"));
         case VALIDATING -> context.actionBar(PlayerMessages.text(
            "fastformer.message.placement_validating", task.validationRemaining()));
         case PREPARING_JOURNAL -> context.actionBar(PlayerMessages.text("fastformer.message.recovery_journal_preparing"));
         case FINALIZING -> context.actionBar(PlayerMessages.text("fastformer.message.placement_finalizing"));
         case COMMITTING -> context.actionBar(PlayerMessages.text("fastformer.message.recovery_journal_finalizing"));
         case GENERATING -> {
            ProgressiveBlockGeneration.Snapshot progress = task.generationProgress();
            context.actionBar(progress == null
               ? PlayerMessages.text("fastformer.message.placement_generating")
               : PlayerMessages.text("fastformer.message.placement_generation_progress",
                  progress.scanned(), progress.estimatedScan(), progress.generated()));
         }
         case ACTIVE -> context.actionBar(PlayerMessages.text(
            "fastformer.message.placement_progress", task.processed(), task.total(), task.placed()));
         default -> throw new IllegalArgumentException("Not a placement progress step: " + step);
      }
   }

   private MutableComponent placementFailureStatus(PlacementTask task, UUID owner, TaskStep step) {
      return switch (step) {
         case EXCEEDED -> PlayerMessages.text("fastformer.message.placement_exceeds_max", task.maxPlacement());
         case MEMORY_UNSAFE -> PlayerMessages.text("fastformer.message.operation_memory_unsafe");
         case JOURNAL_FAILED -> PlayerMessages.text("fastformer.message.recovery_journal_failed");
         case BLOCKED -> PlayerMessages.text("fastformer.message.recovery_journal_blocked");
         default -> switch (task.failurePhase()) {
            case GENERATION -> PlayerMessages.text("fastformer.message.placement_generation_failed");
            case SNAPSHOT -> PlayerMessages.text("fastformer.message.placement_snapshot_validation_failed");
            default -> failureStatus(task, owner);
         };
      };
   }

   private void settleFailedTask(WorldTaskContext context, PlacementTask task, HistoryService history) {
      TaskCancellationResult result = queue.transfer(context, task, history::acceptTransferredTask);
      if (handoverAccepted(result)) {
         queue.remove(context.owner(), task);
      }
      if (result.recoveryCreated()) {
         task.markRecoveryTaskCreated();
      }
   }

   private MutableComponent failureStatus(PlacementTask task, UUID owner) {
      if (task.failurePhase() == WorldOperationPhase.COMMIT && task.hasWrites()) {
         return PlayerMessages.text(
            "fastformer.message.placement_history_unavailable",
            task.operationId().toString()
         );
      }
      String key = task.recoveryTaskCreated()
         ? "fastformer.message.placement_failed_rollback"
         : "fastformer.message.placement_failed_no_recovery";
      return PlayerMessages.text(
         key,
         task.failurePhase().name(),
         task.operationId().toString(),
         task.failureReason()
      );
   }
}
