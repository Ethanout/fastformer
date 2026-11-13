package io.github.fastformer.server.session.execution;

import com.mojang.logging.LogUtils;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.fastplace.geometry.generation.ProgressiveBlockGeneration;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.world.WorldOperationPhase;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.WorldTaskQueue;
import io.github.fastformer.server.task.block.PlacementTask;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Owns queued placement execution, recovery transfer, completion and delayed resumes. */
public final class PlacementTasks implements Scoped {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final WorldTaskQueue<PlacementTask> queue = new WorldTaskQueue<>();
   private final BooleanSupplier closed;

   public PlacementTasks(BooleanSupplier closed) { this.closed = java.util.Objects.requireNonNull(closed); }

   /** Admission is separate from scheduling so queue ownership is established first. */
   public void admit(UUID owner, PlacementTask task) { queue.put(owner, task); }

   public boolean active(UUID owner) { return queue.containsKey(owner); }
   public boolean active(ServerPlayer player) { return active(player.getUUID()); }
   public boolean isCurrent(UUID owner, PlacementTask task) { return queue.get(owner) == task; }

   @Override public void onDiscard() { queue.close(); }

   public TaskCancellationResult cancel(ServerPlayer player) {
      return cancel(new WorldTaskContext(player.getServer(), player.getUUID()), HistorySystem.of(player));
   }

   public TaskCancellationResult cancel(WorldTaskContext context, HistoryService history) {
      PlacementTask task = queue.get(context.owner());
      if (task == null) {
         return TaskCancellationResult.NOT_ACTIVE;
      }
      // Accept first, then drop the queue slot. A refused or failed handover keeps both
      // the task and the extracted snapshot, so the only record of a partial write stays.
      TaskCancellationResult result = queue.transfer(context, task, history::acceptTransferredTask);
      if (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE
         || result == TaskCancellationResult.ROLLBACK_STARTED) {
         queue.remove(context.owner(), task);
      }
      return result;
   }

   /**
    * Hands every write-blocked placement task to the recovery path.
    *
    * <p>The scheduler calls this instead of {@link #tickWorld} while the global write gate
    * is closed. A placement task has no gate check inside its own tick, so it cannot end
    * by itself. The queue slot leaves only after the recovery path accepts the snapshot.
    */
   public void handOverBlockedTasks(net.minecraft.server.MinecraftServer server, HistoryService history) {
      for (UUID owner : queue.owners()) {
         handOverBlockedTask(new WorldTaskContext(server, owner), history);
      }
   }

   private void handOverBlockedTask(WorldTaskContext context, HistoryService history) {
      if (cancel(context, history).handled()) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   public void tickWorld(net.minecraft.server.MinecraftServer server, HistoryService history) {
      for (UUID owner : queue.owners()) {
         WorldTaskContext context = new WorldTaskContext(server, owner);
         if (!context.writesAllowed()) {
            break;
         }
         if (queue.handingOver(owner)) {
            handOverBlockedTask(context, history);
            continue;
         }
         if (history.busy(owner)) {
            continue;
         }
         service(context, history);
      }
   }

   public void enqueue(ServerPlayer player, PlacementTask task) {
      admit(player.getUUID(), task);
      WorldTaskContext context = new WorldTaskContext(player.getServer(), player.getUUID());
      HistoryService history = HistorySystem.of(player);
      context.withResume(() -> resume(context, task, history)).enqueueResume();
   }

   public void resume(WorldTaskContext context, PlacementTask task, HistoryService history) {
      if (!closed.getAsBoolean() && isCurrent(context.owner(), task)
         && !queue.handingOver(context.owner())
         && context.writesAllowed()
         && !history.busy(context.owner())) {
         service(context, history);
      }
   }

   public void service(WorldTaskContext context, HistoryService history) {
      UUID owner = context.owner();
      PlacementTask task = queue.get(owner);
      if (task == null) return;
      try {
         Optional<TaskStep> advanced = queue.tick(context.withResume(() -> resume(context, task, history)), task);
         if (advanced.isEmpty()) return;
         TaskStep step = advanced.orElseThrow();
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
               context.actionBar(FastPlaceMessages.text("fastformer.message.history_saving"));
               return;
            }
            if (historyCommit == JournalPreparation.FAILED) {
               settleFailedTask(context, task, history);
               context.actionBar(failureStatus(task, owner));
               return;
            }
            task.releaseLease(context);
         } else {
            task.releaseAfterCancelledJournal(context);
         }
         queue.remove(owner, task);
         task.releaseMemoryReservation();
         task.releaseCommittedTransactionState();
         if (step == TaskStep.COMPLETE) {
            task.markComplete();
            LOGGER.info("FastFormer placement operation {} finished: {}", task.operationId(), task.metricsSummary());
            context.chat(FastPlaceMessages.text("fastformer.message.placement_placed", task.placed()));
         } else if (step == TaskStep.CONSTRAINTS_FAILED) {
            context.chat(FastPlaceMessages.text("fastformer.message.face_generation_constraints_failed"));
         } else {
            context.actionBar(FastPlaceMessages.text("fastformer.message.operation_empty"));
         }
      } catch (RuntimeException | OutOfMemoryError exception) {
         settleFailedTask(context, task, history);
         LOGGER.error("FastFormer placement task failed for {}: {}", owner, task.metricsSummary(), exception);
         context.actionBar(failureStatus(task, owner));
      }
   }

   private void reportTaskProgress(WorldTaskContext context, PlacementTask task, TaskStep step) {
      switch (step) {
         case WAITING -> context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
         case WORLD_UNLOADED -> context.actionBar(FastPlaceMessages.text("fastformer.message.history_dimension_failed"));
         case VALIDATING -> context.actionBar(FastPlaceMessages.text(
            "fastformer.message.placement_validating", task.validationRemaining()));
         case PREPARING_JOURNAL -> context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_preparing"));
         case FINALIZING -> context.actionBar(FastPlaceMessages.text("fastformer.message.placement_finalizing"));
         case COMMITTING -> context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_finalizing"));
         case GENERATING -> {
            ProgressiveBlockGeneration.Snapshot progress = task.generationProgress();
            context.actionBar(progress == null
               ? FastPlaceMessages.text("fastformer.message.placement_generating")
               : FastPlaceMessages.text("fastformer.message.placement_generation_progress",
                  progress.scanned(), progress.estimatedScan(), progress.generated()));
         }
         case ACTIVE -> context.actionBar(FastPlaceMessages.text(
            "fastformer.message.placement_progress", task.processed(), task.total(), task.placed()));
         default -> throw new IllegalArgumentException("Not a placement progress step: " + step);
      }
   }

   private net.minecraft.network.chat.MutableComponent placementFailureStatus(PlacementTask task, UUID owner, TaskStep step) {
      return switch (step) {
         case EXCEEDED -> FastPlaceMessages.text("fastformer.message.placement_exceeds_max", task.maxPlacement());
         case MEMORY_UNSAFE -> FastPlaceMessages.text("fastformer.message.operation_memory_unsafe");
         case JOURNAL_FAILED -> FastPlaceMessages.text("fastformer.message.recovery_journal_failed");
         case BLOCKED -> FastPlaceMessages.text("fastformer.message.recovery_journal_blocked");
         default -> switch (task.failurePhase()) {
            case GENERATION -> FastPlaceMessages.text("fastformer.message.placement_generation_failed");
            case SNAPSHOT -> FastPlaceMessages.text("fastformer.message.placement_snapshot_validation_failed");
            default -> failureStatus(task, owner);
         };
      };
   }

   private void settleFailedTask(WorldTaskContext context, PlacementTask task, HistoryService history) {
      TaskCancellationResult result = queue.transfer(context, task, history::acceptTransferredTask);
      if (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE || result == TaskCancellationResult.ROLLBACK_STARTED) {
         queue.remove(context.owner(), task);
      }
      if (result.recoveryCreated()) {
         task.markRecoveryTaskCreated();
      }
   }

   private net.minecraft.network.chat.MutableComponent failureStatus(PlacementTask task, UUID owner) {
      if (task.failurePhase() == WorldOperationPhase.COMMIT && task.hasWrites()) {
         return FastPlaceMessages.text(
            "fastformer.message.placement_history_unavailable",
            task.operationId().toString()
         );
      }
      String key = task.recoveryTaskCreated()
         ? "fastformer.message.placement_failed_rollback"
         : "fastformer.message.placement_failed_no_recovery";
      return FastPlaceMessages.text(
         key,
         task.failurePhase().name(),
         task.operationId().toString(),
         task.failureReason()
      );
   }


}
