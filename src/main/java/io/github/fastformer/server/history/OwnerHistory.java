package io.github.fastformer.server.history;

import io.github.fastformer.server.history.compaction.BlockHistoryCompactionRules;

import io.github.fastformer.common.history.HistoryCompressionLimits;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.memory.HistoryCompactionScheduler;
import io.github.fastformer.server.history.memory.HistoryMemoryCache;
import io.github.fastformer.server.history.memory.HistoryOrderCatalog;
import io.github.fastformer.server.history.replay.HistoryReplayTask;
import io.github.fastformer.server.history.rollback.RollbackQueue;

import static io.github.fastformer.server.history.HistoryService.DEFAULT_LIMIT;

import io.github.fastformer.server.task.WorldTaskBudget;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import net.minecraft.server.level.ServerPlayer;
import io.github.fastformer.server.history.replay.HistoryRecoveryPolicy;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.history.vanilla.WorldHistoryEvents;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.mojang.logging.LogUtils;
import io.github.fastformer.server.history.commit.DurableCommit;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import java.util.List;
import org.slf4j.Logger;

/** Owns one player's history queues and the invariants governing admission and retention. */
final class OwnerHistory {
   private static final Logger LOGGER = LogUtils.getLogger();
   static final long MAX_MEMORY_BYTES = 256L * 1024L * 1024L;
   HistoryMemoryCache<HistoryEntry> history;
   HistoryReplayTask active;
   HistoryReplayTask pendingTask;
   final RollbackQueue recovery = new RollbackQueue();
   int deferredUndo;
   int historyLimit = DEFAULT_LIMIT;
   boolean detached;
   final HistoryPersistence persistence = new HistoryPersistence();
   final HistoryLoading<HistoryEntry> loading = new HistoryLoading<HistoryEntry>();
   HistoryOrderCatalog historyOrder;
   PreparedCommit durableCommit;
   HistoryCompressionLimits compactionLimits = HistoryCompressionLimits.DEFAULTS;
   final HistoryCompactionScheduler<HistoryEntry> compaction = new HistoryCompactionScheduler<HistoryEntry>();

   long historyBytes() {
      return history == null ? 0L : history.estimatedBytes();
   }

   HistoryMemoryCache<HistoryEntry> history() {
      if (history == null) {
         history = new HistoryMemoryCache<HistoryEntry>();
      }
      return history;
   }

   HistoryOrderCatalog order() {
      if (historyOrder == null) {
         HistoryMemoryCache<HistoryEntry> memory = history();
         historyOrder = HistoryOrderCatalog.merge(
            memory.operationIds(true), memory.operationIds(false), List.of(), List.of());
      }
      return historyOrder;
   }

   void mergeSnapshot(HistoryRepository.Snapshot<HistoryEntry> loaded) {
      HistoryMemoryCache<HistoryEntry> memory = history();
      memory.merge(true, loaded.undo());
      memory.merge(false, loaded.redo());
      historyOrder = HistoryOrderCatalog.merge(
         memory.operationIds(true), memory.operationIds(false), loaded.undoOrder(), loaded.redoOrder());
      historyOrder.trim(historyLimit);
      memory.trim(historyLimit, MAX_MEMORY_BYTES);
   }

   JournalPreparation pollCommit(WorldTaskContext context, HistoryEntry batch,
      PersistentRecoveryJournal journal, HistoryRepository<HistoryEntry> repository) {
      PreparedCommit pending = durableCommit;
      if (pending == null) {
         durableCommit = prepareDurableCommit(context, batch, journal, repository);
         return JournalPreparation.PENDING;
      }
      if (!pending.batch().operationId().equals(batch.operationId()) || pending.journal() != journal) {
         return JournalPreparation.FAILED;
      }
      switch (pending.publication().poll()) {
         case PUBLISH_BATCH, SEAL_JOURNAL, PUBLISH_INDEX -> { return JournalPreparation.PENDING; }
         case FAILED -> {
            durableCommit = null;
            return JournalPreparation.FAILED;
         }
         case DONE -> { }
      }
      addBatchInMemory(batch);
      WorldHistoryEvents.send(context, batch, io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload.Kind.RECORD, true);
      if (journal != null) journal.historyPublished();
      durableCommit = null;
      return JournalPreparation.READY;
   }

   private PreparedCommit prepareDurableCommit(
      WorldTaskContext context, HistoryEntry batch, PersistentRecoveryJournal journal, HistoryRepository<HistoryEntry> repository
   ) {
      DurableCommit publication = new DurableCommit(new DurableCommit.Actions() {
         @Override public CompletableFuture<Void> publishBatch() {
            return repository.publishBatchOnly(context.owner(), batch);
         }

         @Override public boolean sealJournal() {
            return journal == null || journal.sealFinalizedForHistory();
         }

         @Override public CompletableFuture<Void> publishIndex() {
            HistoryOrderCatalog prospective = order().copy();
            prospective.addNew(batch.operationId());
            prospective.trim(historyLimit);
            return repository.publishIndex(context.owner(), prospective.order(true), prospective.order(false)
            );
         }
      }, 100);
      return new PreparedCommit(batch, journal, publication);
   }

   private void addBatchInMemory(HistoryEntry batch) {
      HistoryMemoryCache<HistoryEntry> history = history();
      history.addNew(batch);
      history.trim(historyLimit, MAX_MEMORY_BYTES);
      HistoryOrderCatalog order = order();
      order.addNew(batch.operationId());
      order.trim(historyLimit);
   }

   void publishNew(WorldTaskContext context, HistoryEntry batch, HistoryRepository<HistoryEntry> repository) {
      HistoryOrderCatalog order = order();
      order.addNew(batch.operationId());
      order.trim(historyLimit);
      trackPersistence(context, false, repository.publishNewBatch(context.owner(), batch, order.order(true), order.order(false)
      ));
   }

   void publishIndex(WorldTaskContext context, HistoryRepository<HistoryEntry> repository) {
      trackPersistence(context, false, repository.publishIndex(context.owner(), order().order(true), order().order(false)
      ));
   }

   void retryPersistence(WorldTaskContext context, HistoryRepository<HistoryEntry> repository) {
      if (this.history == null || context.server() == null || !persistence.retryReady()) return;
      HistoryMemoryCache<HistoryEntry> history = this.history;
      HistoryOrderCatalog order = order();
      trackPersistence(context, true, repository.publishSnapshot(context.owner(), history.snapshot(true), history.snapshot(false),
         order.order(true), order.order(false)
      ));
   }

   void trackPersistence(WorldTaskContext context, boolean clearsDirty, CompletableFuture<Void> publication) {
      if (context.server() == null) return;
      persistence.track(clearsDirty, publication, context.server()::execute, dirty -> {
         var player = context.onlinePlayer();
         if (player != null) PlayerMessages.chat(player, PlayerMessages.text(dirty
            ? "fastformer.message.history_save_retry" : "fastformer.message.history_save_recovered"));
      });
   }

   void trimToPlayerSetting(ServerPlayer player, HistoryMemoryCache<HistoryEntry> history) {
      try {
         historyLimit = Math.clamp(FastPlaceSettings.load(player).worldUndoHistoryLimit(), 1, HistoryService.MAX_LIMIT);
         history.trim(historyLimit, MAX_MEMORY_BYTES);
      } catch (RuntimeException failure) {
         // Retain the committed batch and retry retention later if settings or trimming fail.
         LOGGER.error("FastFormer history trimming failed for {}", player.getUUID(), failure);
      }
   }

   boolean busy() {
      return workPending() || snapshotLoadFailed();
   }

   boolean needsTick() {
      // A failed load needs an explicit retry. Dirty persistence and compaction
      // need ticks, but do not by themselves block an editor from continuing.
      return workPending()
         || persistence.dirty()
         || history != null && !detached && compaction.needsTick(history, new BlockHistoryCompactionRules(compactionLimits, HistoryStorageConfig.compactionPolicy()));
   }

   private boolean workPending() {
      return loading.pending()
         || active != null
         || pendingTask != null
         || !recovery.isEmpty()
         || deferredUndo > 0;
   }

   /**
    * A failed snapshot remains blocked even if its merge allocated a partial cache.
    *
    * <p>An older-page failure must not use this flag. That owner already holds
    * decoded batches and a durable order, so a later undo can retry the page.</p>
    */
   boolean snapshotLoadFailed() {
      return loading.snapshotFailed();
   }

   boolean canEvict() {
      return detached
         && !busy()
         // Until disk-backed history is attached, these stacks are the only
         // undo/redo copy. A detached owner may still hold useful history.
         && (history == null || history.isEmpty())
         && persistence.settled()
         && !compaction.running()
         && durableCommit == null;
   }

   /** Advances the owned replay, retaining failed work and releasing terminal resources. */
   boolean tickReplay(WorldTaskContext context, HistoryReplayTask.HistoryAccess replayAccess) {
      HistoryReplayTask task = active;
      if (task == null) return false;
      if (!context.writesAllowed() && !task.recovery()) {
         // This server's gate may close after an undo/redo was accepted. Do not
         // apply more history targets; requestCancel either discards its
         // unused journal or rolls its already-applied cells back.
         task.requestCancel();
      }
      boolean finished;
      WorldTaskBudget budget = WorldTaskBudget.forServerTick(
         task.memoryThrottled(), task.previousBatchCells(), task.previousBatchNanos()
      );
      long batchStartedAt = System.nanoTime();
      try {
         finished = task.tick(context, budget);
      } catch (RuntimeException | OutOfMemoryError exception) {
         // Never let a malformed block entity or callback crash the server.
         // Roll back any cells already touched by this task on the next tick.
         task.requestCancel();
         task.releaseMemoryReservation();
         active = null;
         pendingTask = task;
         context.actionBar(PlayerMessages.text("fastformer.message.restore_failed_retry"));
          LOGGER.error(
             "FastFormer history task failed for {} (operation={}) and was retained for recovery: {}",
             context.owner(),
             task.operationId(),
             task.metricsSummary(),
             exception
          );
          return false;
       } finally {
          task.recordBatch(budget.consumed(), System.nanoTime() - batchStartedAt);
       }
      if (finished) {
         task.clearConflictPrompt();
         if (task.completedSuccessfully()) {
            task.markMetricsComplete();
         }
         LOGGER.info("FastFormer history operation {} finished: {}", task.operationId(), task.metricsSummary());
         // Every terminal tick owns the lease cleanup.  Most phases release
         // it on their way to RESOLVE, but cancellation/conflict exits can
         // finish directly and must not strand the dimension lock.
         task.releaseLease();
         task.releaseMemoryReservation();
         active = null;
         HistoryRecoveryPolicy.RetentionAction retention = HistoryRecoveryPolicy.retentionAction(
            task.recovery() && task.retainRecovery(),
            task.batch() != null
         );
         if (retention == HistoryRecoveryPolicy.RetentionAction.RETRY_AUTOMATICALLY) {
            active = task.retryRecovery();
         }
      }
      return finished;
   }

   boolean cancelReplay() {
      HistoryReplayTask task = active == null ? pendingTask : active;
      if (task == null) return loading.pages().cancelPageLoad();
      if (task.recovery()) return false;
      loading.pages().cancelPageLoad();
      task.requestCancel();
      return true;
   }

   void detach() {
      loading.pages().cancelPageLoad();
      if (active != null && !active.recovery()) active.requestCancel();
      if (pendingTask != null && !pendingTask.recovery()) pendingTask.requestCancel();
      detached = true;
      compaction.clear();
   }

   int takeDeferredUndo() {
      if (active != null || pendingTask != null || !recovery.isEmpty()) return 0;
      int count = deferredUndo;
      deferredUndo = 0;
      return count;
   }

   void attachPending(WorldTaskContext context,
      java.util.function.Function<RollbackQueue.ReadyRecovery, HistoryReplayTask> recover) {
      if (active != null) return;
      if (pendingTask != null) {
         active = pendingTask;
         pendingTask = null;
         return;
      }
      recovery.poll(context, ready -> active = recover.apply(ready));
   }

   void discard() {
      persistence.close();
      loading.close();
      compaction.clear();
      releaseTask(active);
      if (pendingTask != active) releaseTask(pendingTask);
   }

   private void releaseTask(HistoryReplayTask task) {
      if (task == null) return;
      try {
         task.cancelJournalPreparation();
      } catch (RuntimeException | OutOfMemoryError failure) {
         LOGGER.error("Could not cancel a history task journal during service cleanup", failure);
      } finally {
         try {
            task.releaseMemoryReservation();
         } catch (RuntimeException | OutOfMemoryError failure) {
            LOGGER.error("Could not release a history task reservation during service cleanup", failure);
         }
      }
   }

   record PreparedCommit(
      HistoryEntry batch, PersistentRecoveryJournal journal, DurableCommit publication
   ) {}
}
