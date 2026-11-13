package io.github.fastformer.server.session.execution;

import com.mojang.logging.LogUtils;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.submission.WorkspaceResultSink;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.WorldTaskQueue;
import io.github.fastformer.server.task.block.SubmittedWorkspaceTask;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Owns selection/workspace writers, recovery transfer and their completion receipts. */
public final class OperationTasks implements Scoped {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final WorldTaskQueue<WorldTask> queue = new WorldTaskQueue<>();
   private final BooleanSupplier closed;
   private final Predicate<UUID> placementsBusy;
   private final Function<WorldTaskContext, WorkspaceResultSink> results;

   public OperationTasks(BooleanSupplier closed, Predicate<UUID> placementsBusy,
      Function<WorldTaskContext, WorkspaceResultSink> results) {
      this.closed = Objects.requireNonNull(closed);
      this.placementsBusy = Objects.requireNonNull(placementsBusy);
      this.results = Objects.requireNonNull(results);
   }

   /** Record ownership before publishing admission or scheduling a resume. */
   public void admit(UUID owner, WorldTask task) { queue.put(owner, task); }
   public boolean active(UUID owner) { return queue.containsKey(owner); }
   public boolean active(ServerPlayer player) { return active(player.getUUID()); }
   @Override public void onDiscard() { queue.close(); }

   /** Schedules the first resume of one queued task. A scheduling fault keeps the task. */
   public void scheduleResume(ServerPlayer player, WorldTask task) {
      try {
         WorldTaskContext context = new WorldTaskContext(player.getServer(), player.getUUID());
         HistoryService history = HistorySystem.of(player);
         context.withResume(() -> resume(context, task, history)).enqueueResume();
      } catch (RuntimeException exception) {
         LOGGER.warn("FastFormer queued a workspace submission but could not schedule its resume", exception);
      }
   }

   public TaskCancellationResult cancel(ServerPlayer player) {
      return cancel(new WorldTaskContext(player.getServer(), player.getUUID()), HistorySystem.of(player));
   }

   /**
    * Hands every write-blocked operation task to the recovery path.
    *
    * <p>The scheduler calls this instead of {@link #tickWorld} while the global write gate
    * is closed. A task must not run again, and it must not be dropped either: the queue
    * slot leaves only after the recovery path accepts the snapshot. A refused or failed
    * handover keeps the task, so a later tick can try again.
    */
   public void handOverBlockedTasks(MinecraftServer server, HistoryService history) {
      for (UUID owner : queue.owners()) {
         handOverBlockedTask(new WorldTaskContext(server, owner), history);
      }
   }

   private void handOverBlockedTask(WorldTaskContext context, HistoryService history) {
      TaskCancellationResult handover = cancel(context, history);
      if (handover.handled()) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   /**
    * Stops one queued task and records its workspace outcome when recovery accepts it.
    *
    * <p>This is the only owner of that result packet. A later tick, a cancel, or a
    * closed-gate handover all reach it, so one transfer cannot receive two answers.
    * Recovery does not finish the ledger: an {@code IN_PROGRESS} record never ages out,
    * and a missing finish would leave the client waiting.</p>
    *
    * @return {@link TaskCancellationResult#NOT_ACTIVE} when no task exists
    */
   public TaskCancellationResult cancel(WorldTaskContext context, HistoryService history) {
      WorldTask task = queue.get(context.owner());
      if (task == null) {
         return TaskCancellationResult.NOT_ACTIVE;
      }
      TaskCancellationResult handover = queue.transfer(context, task, history::acceptTransferredTask);
      if (handover != TaskCancellationResult.CANCELLED_BEFORE_WRITE
         && handover != TaskCancellationResult.ROLLBACK_STARTED) {
         return handover;
      }
      queue.remove(context.owner(), task);
      if (task instanceof SubmittedWorkspaceTask workspaceTask) {
         // No writes: the draft may go again with a new transfer. Writes that reached
         // recovery report that state, so a retry cannot repeat the same work.
         results.apply(context).finish(workspaceTask.completion(context.owner(), false,
            handover == TaskCancellationResult.CANCELLED_BEFORE_WRITE,
            handover == TaskCancellationResult.ROLLBACK_STARTED), context.onlinePlayer());
      }
      return handover;
   }

   public void tickWorld(MinecraftServer server, HistoryService history) {
      for (UUID owner : queue.owners()) {
         WorldTaskContext context = new WorldTaskContext(server, owner);
         if (!context.writesAllowed()) {
            break;
         }
         if (queue.handingOver(owner)) {
            handOverBlockedTask(context, history);
            continue;
         }
         if (history.busy(owner) || placementsBusy.test(owner)) {
            continue;
         }
         service(context, history);
      }
   }

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
            context.actionBar(FastPlaceMessages.text("fastformer.message.history_saving"));
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
         case WAITING -> FastPlaceMessages.text("fastformer.message.world_write_waiting");
         case WORLD_UNLOADED -> FastPlaceMessages.text("fastformer.message.history_dimension_failed");
         default -> FastPlaceMessages.text("fastformer.message.operation_phase", task.phaseName());
      });
   }

   private MutableComponent completionStatus(WorldTask task, TaskStep step, boolean recoveryCreated) {
      return switch (step) {
         case COMPLETE -> FastPlaceMessages.text("fastformer.message.operation_complete");
         case EMPTY -> FastPlaceMessages.text("fastformer.message.operation_empty");
         case FAILED -> operationFailureStatus(task, recoveryCreated);
         case JOURNAL_FAILED -> FastPlaceMessages.text("fastformer.message.recovery_journal_failed");
         case MEMORY_UNSAFE -> FastPlaceMessages.text("fastformer.message.operation_memory_unsafe");
         default -> FastPlaceMessages.text("fastformer.message.operation_too_large_run");
      };
   }

   public void enqueue(ServerPlayer player, WorldTask task) {
      admit(player.getUUID(), task);
      WorldTaskContext context = new WorldTaskContext(player.getServer(), player.getUUID());
      HistoryService history = HistorySystem.of(player);
      context.withResume(() -> resume(context, task, history)).enqueueResume();
   }

   private void resume(WorldTaskContext context, WorldTask task, HistoryService history) {
      if (!closed.getAsBoolean() && queue.get(context.owner()) == task
         && !queue.handingOver(context.owner())
         && context.writesAllowed()
         && !history.busy(context.owner())
         && !placementsBusy.test(context.owner())) {
         service(context, history);
      }
   }


   private boolean handoverAccepted(TaskCancellationResult result) {
      return result == TaskCancellationResult.CANCELLED_BEFORE_WRITE || result == TaskCancellationResult.ROLLBACK_STARTED;
   }

   private MutableComponent operationFailureStatus(
      WorldTask task, boolean recoveryCreated
   ) {
      String key = recoveryCreated
         ? "fastformer.message.operation_failed_rollback"
         : "fastformer.message.operation_failed_no_recovery";
      return FastPlaceMessages.text(key, task.phaseName(), task.operationId(), task.metricsSummary());
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
