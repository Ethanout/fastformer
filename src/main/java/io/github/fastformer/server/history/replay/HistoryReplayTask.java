package io.github.fastformer.server.history.replay;

import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.memory.HistoryMemoryCache;
import io.github.fastformer.server.history.vanilla.WorldHistoryEvents;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.fastplace.world.WorldBatchFeedback;
import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import io.github.fastformer.fastplace.world.WorldOperationPhase;
import io.github.fastformer.server.task.WorldTaskBudget;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.runtime.TaskWriteLease;
import io.github.fastformer.fastplace.world.memory.MemoryAdmission;
import io.github.fastformer.fastplace.world.memory.MemoryReservation;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import io.github.fastformer.protocol.c2s.world.HistoryConflictResponsePayload;
import java.util.BitSet;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/** Executes one undo, redo, or recovery sequence while retaining its write lease. */
public final class HistoryReplayTask {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final HistoryAccess historyAccess;
   private final EntryReplayRegistry replayTypes;

   public enum PageState { WAITING, FAILED, EXHAUSTED }

   public interface HistoryAccess {
      PageState requestPage(WorldTaskContext context, boolean undo, int remaining);
      void commit(WorldTaskContext context, HistoryMemoryCache<HistoryEntry> history, boolean undo);
   }

   public boolean recovery() {
      return recovery;
   }

   private final HistoryMemoryCache<HistoryEntry> history;
   private final boolean undo;
   private final int requested;
   private final boolean recovery;
   private final PlacementUpdateMode updateMode;
   private ReplayJournal journal;
   private HistoryEntry batch;
   private int completed;
   private int index;
   private Phase phase = Phase.CHECK;
   private BitSet applied;
   private boolean cancelRequested;
   private boolean dimensionNoticeSent;
   private boolean retainRecovery;
   private int skippedConflicts;
   private int durabilityRetryTicks;
   private final TaskWriteLease writeLease = new TaskWriteLease();
   private int partialApplyIndex = -1;
   private java.util.function.Predicate<ServerLevel> partialApplyState;
   private int blockedApplyIndex = -1;
   private boolean blockedDuringRollback;
   private int blockedNoticeTicks;
   private MemoryReservation memoryReservation;
   private boolean memoryThrottled;
   private boolean completedSuccessfully;
   private final WorldBatchFeedback batchFeedback;
   private UUID operationId;
   private WorldOperationMetrics metrics;
   private final BitSet conflicts = new BitSet();
   private BitSet confirmedConflicts = new BitSet();
   private HistoryConflictPrompt conflictPrompt;
   private HistoryConflictResponsePayload.Choice conflictChoice;
   private EntryReplayState replayState;

   public HistoryReplayTask(
      HistoryAccess historyAccess, HistoryMemoryCache<HistoryEntry> history, boolean undo, int requested,
      PlacementUpdateMode updateMode, EntryReplayRegistry replayTypes
   ) {
      this.historyAccess = historyAccess;
      this.replayTypes = java.util.Objects.requireNonNull(replayTypes, "replay types");
      this.history = history;
      this.undo = undo;
      this.requested = requested;
      this.recovery = false;
      this.updateMode = HistoryRecoveryPolicy.effectiveUpdateMode(updateMode, false);
      this.journal = null;
      HistoryEntry first = history == null
         ? null
         : history.peek(undo);
      this.operationId = first == null || first.operationId() == null
         ? UUID.randomUUID()
         : first.operationId();
      this.metrics = new WorldOperationMetrics(this.operationId);
      this.metrics.queued();
      this.batchFeedback = new WorldBatchFeedback(this.metrics);
   }

   private HistoryReplayTask(HistoryAccess historyAccess, HistoryEntry batch, ReplayJournal journal,
      EntryReplayRegistry replayTypes, UUID operationId) {
      this.historyAccess = historyAccess;
      this.replayTypes = replayTypes;
      this.history = null;
      this.undo = true;
      this.requested = 1;
      this.recovery = true;
      this.updateMode = HistoryRecoveryPolicy.effectiveUpdateMode(null, true);
      this.batch = batch;
      this.journal = journal;
      this.replayState = batch == null ? null : replayTypes.open(batch, true);
      this.operationId = operationId == null ? UUID.randomUUID() : operationId;
      this.metrics = new WorldOperationMetrics(this.operationId);
      this.metrics.queued();
      this.batchFeedback = new WorldBatchFeedback(this.metrics);
   }

   public static HistoryReplayTask recovery(HistoryAccess historyAccess, HistoryEntry batch, ReplayJournal journal,
      EntryReplayRegistry replayTypes, UUID operationId) {
      return new HistoryReplayTask(historyAccess, batch, journal, replayTypes, operationId);
   }

   public HistoryReplayTask retryRecovery() {
      if (!recovery) throw new IllegalStateException("Only recovery tasks can be retried as recovery");
      return recovery(historyAccess, batch, journal, replayTypes, operationId);
   }

   public UUID operationId() {
      return this.operationId;
   }

   public boolean completedSuccessfully() {
      return this.completedSuccessfully;
   }

   public void markMetricsComplete() {
      this.metrics.complete();
   }

   /** Returns true once the caller can retire this task. */
   public boolean tick(WorldTaskContext context, WorldTaskBudget budget) {
      while (budget.hasRemaining()) {
         metrics.phase(metricsPhase(phase));
         Step preparation = prepareBatch(context);
         if (preparation != Step.NEXT) {
            return preparation == Step.FINISHED;
         }
         if (HistoryRecoveryPolicy.canFinishCancellation(
            this.cancelRequested,
            this.phase == Phase.ROLLBACK,
            this.phase == Phase.RESOLVE,
            this.phase == Phase.FAILED
         )) {
            clearConflictPrompt();
            this.cancelJournalPreparation();
            if (!this.recovery) {
               this.releaseAfterCancelledJournal();
            }
            context.actionBar(PlayerMessages.text(
               this.phase == Phase.CONFLICT ? "fastformer.message.cancelled" : "fastformer.message.restore_failed_retry"
            ));
            return true;
         }
         ServerLevel level = context.level(this.batch.dimension());
         if (level == null) {
            // The batch remains durable in history/journal, so do not hold
            // an in-memory working-set reservation while its dimension is unloaded.
            this.metrics.phase(WorldOperationPhase.WORLD_UNLOADED);
            this.releaseMemoryReservation();
            if (!this.dimensionNoticeSent) {
               context.actionBar(PlayerMessages.text("fastformer.message.history_dimension_failed"));
               this.dimensionNoticeSent = true;
            }
            return false;
         }
         this.dimensionNoticeSent = false;
         if (this.phase == Phase.CONFLICT) {
            if (conflictPrompt == null || !conflictPrompt.tick()) {
               clearConflictPrompt();
               return true;
            }
            return false;
         }
         if (!this.ensureMemoryReservation()) {
            context.actionBar(PlayerMessages.text("fastformer.message.operation_memory_unsafe"));
            return false;
         }
         if (!this.acquireLease(context)) {
            context.actionBar(PlayerMessages.text("fastformer.message.world_write_waiting"));
            return false;
         }
         Step step = switch (phase) {
            case CHECK -> checkBatch(context, level, budget);
            case JOURNAL -> prepareBatchJournal(context);
            case APPLY -> applyBatch(context, level, budget);
            case ROLLBACK -> rollbackBatch(context, level, budget);
            case RESOLVE -> resolveJournal(context);
            case FAILED -> retryBlockedWrite(context, level);
            case CONFLICT -> Step.YIELD;
         };
         if (step != Step.NEXT) {
            return step == Step.FINISHED;
         }
      }
      reportProgress(context);
      return false;
   }

   private enum Step { NEXT, YIELD, FINISHED }

   private Step prepareBatch(WorldTaskContext context) {
      if (this.recovery && this.batch == null) {
         this.retainRecovery = true;
         context.actionBar(PlayerMessages.text("fastformer.message.history_conflict"));
         return Step.FINISHED;
      }
      if (this.batch == null) {
         this.batch = this.history.peek(this.undo);
         if (this.batch == null) {
            if (!this.recovery) {
               this.releaseLease();
               if (this.cancelRequested) {
                  context.actionBar(PlayerMessages.text("fastformer.message.cancelled"));
                  return Step.FINISHED;
               }
               PageState page = historyAccess.requestPage(context, undo, requested - completed);
               if (page == PageState.WAITING) {
                  return Step.YIELD;
               }
               if (page == PageState.FAILED) {
                  return Step.FINISHED;
               }
               this.completedSuccessfully = true;
               context.actionBar(
                  PlayerMessages.text(
                     this.undo ? "fastformer.message.history_complete_undo" : "fastformer.message.history_complete_redo", this.completed
                  )
               );
            }
            return Step.FINISHED;
         }
         if (this.batch.operationId() != null) {
            this.operationId = this.batch.operationId();
            this.metrics = new WorldOperationMetrics(this.operationId);
            this.metrics.queued();
            this.batchFeedback.attach(this.metrics);
         }
         this.metrics.targetCount(this.batch.size());
         this.index = 0;
         this.phase = Phase.CHECK;
         this.applied = null;
         this.conflicts.clear();
         this.confirmedConflicts.clear();
         this.conflictChoice = null;
         resetReplayState();
      }
      return Step.NEXT;
   }

   private Step retryBlockedWrite(WorldTaskContext context, ServerLevel level) {
      if (this.resumeBlockedApply(level)) {
         return Step.NEXT;
      }
      this.retainRecovery = true;
      if (this.blockedNoticeTicks-- <= 0) {
         this.blockedNoticeTicks = 100;
         context.actionBar(PlayerMessages.text("fastformer.message.task_recovery_blocked"));
      }
      return Step.YIELD;
   }

   private Step resolveJournal(WorldTaskContext context) {
      if (this.durabilityRetryTicks > 0) {
         this.durabilityRetryTicks--;
         return Step.YIELD;
      }
      if (this.journal != null && !this.journal.resolve(context)) {
         this.durabilityRetryTicks = 100;
         context.actionBar(PlayerMessages.text("fastformer.message.restore_failed_retry"));
         return Step.YIELD;
      }
      this.releaseLease();
      this.journal = null;
      if (this.recovery && this.skippedConflicts > 0) {
         context.actionBar(PlayerMessages.text("fastformer.message.restore_complete_conflicts", this.skippedConflicts));
      } else {
         context.actionBar(
            PlayerMessages.text(
               this.recovery
                  ? "fastformer.message.restore_complete"
                  : "fastformer.message.history_conflict"
            )
         );
      }
      if (this.recovery && !this.cancelRequested) {
         this.completedSuccessfully = true;
      }
      return Step.FINISHED;
   }

   private Step checkBatch(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      if (this.recovery) {
         // Recovery is best-effort per cell: another player may have
         // intentionally changed one position after our write. Restore
         // every cell still owned by this task and preserve conflicts.
         this.index = 0;
         this.applied = new BitSet(this.batch.size());
         this.phase = Phase.JOURNAL;
         return Step.NEXT;
      }
      while (this.index < this.batch.size() && budget.tryConsume()) {
         if (this.batch.match(level, this.index, this.undo) == 0) {
            if (this.confirmedConflicts.get(this.index)) {
               boolean skip = conflictChoice == HistoryConflictResponsePayload.Choice.SKIP;
               if (!replayState.captureConflict(level, index, skip)) {
                  context.actionBar(PlayerMessages.text("fastformer.message.history_dimension_failed"));
                  return Step.FINISHED;
               }
            } else {
               conflicts.set(this.index);
            }
         }
         this.index++;
      }
      if (this.index < this.batch.size()) {
         return Step.NEXT;
      }
      this.index = 0;
      if (!conflicts.isEmpty()) {
         this.phase = Phase.CONFLICT;
         this.releaseLease();
         resetReplayState();
         this.releaseMemoryReservation();
         this.conflictPrompt = HistoryConflictPrompt.begin(context, batch, conflicts).orElse(null);
         return conflictPrompt == null ? Step.FINISHED : Step.YIELD;
      }
      this.applied = new BitSet(this.batch.size());
      this.phase = Phase.JOURNAL;
      return Step.NEXT;
   }

   private Step prepareBatchJournal(WorldTaskContext context) {
      JournalPreparation preparation = this.prepareJournal(context);
      if (preparation == JournalPreparation.PENDING) {
         context.actionBar(PlayerMessages.text("fastformer.message.recovery_journal_preparing"));
         return Step.YIELD;
      }
      if (preparation == JournalPreparation.FAILED) {
         this.releaseLease();
         this.metrics.phase(WorldOperationPhase.FAILED);
         this.retainRecovery = this.recovery;
         LOGGER.error(
            "FastFormer recovery journal preparation failed operation={} phase={} metrics={}",
            this.operationId,
            this.phase,
            this.metrics.summary()
         );
         context.actionBar(PlayerMessages.text("fastformer.message.recovery_journal_failed"));
         return Step.FINISHED;
      }
      this.phase = Phase.APPLY;
      return Step.NEXT;
   }

   private Step applyBatch(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      while (this.index < this.batch.size() && budget.tryConsume()) {
         int match = this.replayState.match(level, this.index);
         if (match == 2) {
            this.index++;
            continue;
         }
         if (match != 1) {
            if (HistoryRecoveryPolicy.cellAction(this.recovery, match)
               == HistoryRecoveryPolicy.CellAction.PRESERVE_EXTERNAL) {
               this.skippedConflicts++;
               this.index++;
               continue;
            }
            this.retainRecovery = this.recovery;
            this.phase = Phase.ROLLBACK;
            this.index = this.batch.size() - 1;
            break;
         }
         this.applied.set(this.index);
         boolean applied = this.batch.apply(level, this.index, this.undo, this.updateMode.flags());
         this.metrics.writeAttempt(applied);
         if (!applied) {
            var partial = replayState.capturePartial(level, this.index);
            if (partial.isEmpty()) {
               this.blockApplyFailure(this.index, false);
               LOGGER.error(
                  "FastFormer history write failed operation={} phase={} position={} metrics={}",
                  this.operationId,
                  this.phase,
                  this.batch.position(this.index).toShortString(),
                  this.metrics.summary()
               );
               break;
            }
            this.partialApplyIndex = this.index;
            this.partialApplyState = partial.orElseThrow();
            this.retainRecovery = this.recovery;
            this.phase = Phase.ROLLBACK;
            this.index = this.batch.size() - 1;
            break;
         }
         this.index++;
      }
      if (this.phase == Phase.ROLLBACK) {
         return Step.NEXT;
      }
      if (this.index < this.batch.size()) {
         return Step.NEXT;
      }
      if (!this.recovery) {
         if (this.journal != null && !this.journal.complete()) {
            this.phase = Phase.ROLLBACK;
            this.index = this.batch.size() - 1;
            return Step.NEXT;
         }
         commitBatch(context);
         WorldHistoryEvents.send(context, this.batch, this.undo
            ? io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload.Kind.UNDO
            : io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload.Kind.REDO,
            !this.replayState.hasSkippedCells());
         this.journal = null;
      }
      this.completed++;
      if (this.recovery || this.completed >= this.requested) {
         if (this.recovery) {
            this.phase = Phase.RESOLVE;
            this.durabilityRetryTicks = 0;
            return Step.NEXT;
         }
         this.releaseLease();
         this.completedSuccessfully = true;
         context.actionBar(
            PlayerMessages.text(
               this.undo ? "fastformer.message.history_complete_undo" : "fastformer.message.history_complete_redo",
               this.completed
            )
         );
         return Step.FINISHED;
      }
      this.batch = null;
      return Step.NEXT;
   }

   private Step rollbackBatch(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      // A race during APPLY is handled without overwriting the external
      // write: restore only entries that still contain our target state.
      while (this.index >= 0 && budget.tryConsume()) {
         if (this.applied == null) {
            this.phase = Phase.FAILED;
            break;
         }
         int appliedIndex = this.applied.previousSetBit(this.index);
         if (appliedIndex < 0) {
            // A conflict can occur before the first write after CHECK.
            // An empty applied set is already fully rolled back.
            this.index = -1;
            break;
         }
         boolean inverseUndo = !this.undo;
         boolean partialIndexMatches = appliedIndex == this.partialApplyIndex;
         boolean partialFingerprintMatches = partialIndexMatches
            && this.partialApplyState != null
            && this.partialApplyState.test(level);
         boolean normalTargetMatches = this.batch.match(level, appliedIndex, inverseUndo) == 1;
         if (HistoryRecoveryPolicy.ownsPartialRollback(
            partialIndexMatches, partialFingerprintMatches, normalTargetMatches
         )) {
            boolean rolledBack = this.replayState.rollback(level, appliedIndex, PlacementUpdateMode.CLIENT_ONLY.flags());
            this.metrics.writeAttempt(rolledBack);
            if (!rolledBack) {
               var partial = replayState.capturePartial(level, appliedIndex);
               if (partial.isEmpty()) {
                  this.blockApplyFailure(appliedIndex, true);
                  LOGGER.error(
                     "FastFormer history rollback failed operation={} phase={} position={} metrics={}",
                     this.operationId,
                     this.phase,
                     this.batch.position(appliedIndex).toShortString(),
                     this.metrics.summary()
                  );
                  break;
               }
               this.partialApplyIndex = appliedIndex;
               this.partialApplyState = partial.orElseThrow();
               context.actionBar(PlayerMessages.text("fastformer.message.restore_failed_retry"));
               return Step.YIELD;
            }
         }
         if (appliedIndex == this.partialApplyIndex) {
            this.partialApplyIndex = -1;
            this.partialApplyState = null;
         }
         this.index = appliedIndex - 1;
      }
      if (this.phase == Phase.FAILED) {
         this.retainRecovery = true;
         context.actionBar(PlayerMessages.text("fastformer.message.task_recovery_blocked"));
         return Step.YIELD;
      }
      if (this.index < 0) {
         if (!this.recovery) {
            this.phase = Phase.RESOLVE;
            this.durabilityRetryTicks = 0;
            return Step.NEXT;
         }
         this.retainRecovery = this.recovery;
         context.actionBar(
            PlayerMessages.text(
               "fastformer.message.history_conflict"
            )
         );
         return Step.FINISHED;
      }
      return Step.NEXT;
   }

   private void reportProgress(WorldTaskContext context) {
      if (this.batch == null || this.batch.size() <= 0) {
         return;
      }
      int processed = switch (this.phase) {
         case CHECK, CONFLICT, JOURNAL, APPLY -> Math.clamp(this.index, 0, this.batch.size());
         case ROLLBACK, FAILED, RESOLVE -> Math.clamp(this.batch.size() - 1 - this.index, 0, this.batch.size());
      };
      if (this.recovery) {
         context.actionBar(PlayerMessages.text(
            "fastformer.message.recovery_progress", processed, this.batch.size()
         ));
      } else {
         context.actionBar(PlayerMessages.text(
            this.undo ? "fastformer.message.history_progress_undo" : "fastformer.message.history_progress_redo",
            Math.min(this.completed + 1, this.requested),
            this.requested,
            processed,
            this.batch.size()
         ));
      }
   }

   private void commitBatch(WorldTaskContext context) {
      historyAccess.commit(context, history, undo);
   }

   private JournalPreparation prepareJournal(WorldTaskContext context) {
      if (this.recovery) {
         return JournalPreparation.READY;
      }
      JournalPreparation preparation = this.journal.prepare(context, operationId);
      if (preparation == JournalPreparation.PENDING) {
         return preparation;
      }
      if (preparation == JournalPreparation.FAILED) {
         this.metrics.phase(WorldOperationPhase.JOURNAL);
         LOGGER.error(
            "FastFormer history journal preparation failed operation={} phase={} reason={}",
            this.operationId,
            this.phase,
            this.journal.failureReason()
         );
         return preparation;
      }
      return preparation;
   }

   public void cancelJournalPreparation() {
      if (journal != null) journal.cancelPreparation();
   }

   private void resetReplayState() {
      replayState = replayTypes.open(batch, undo);
      journal = replayState.journal();
   }

   private boolean ensureMemoryReservation() {
      if (this.memoryReservation != null) {
         return true;
      }
      this.metrics.phase(WorldOperationPhase.MEMORY_ADMISSION);
      MemoryAdmission admission = WorldOperationMemory.recoveryWorkingSetAdmission(
         this.batch.size(),
         !this.recovery && this.journal == null
      );
      if (!admission.allowed()) {
         return false;
      }
      this.memoryThrottled = admission.throttled();
      this.memoryReservation = WorldOperationMemory.reserve(admission).orElse(null);
      return this.memoryReservation != null;
   }

   public void releaseMemoryReservation() {
      if (this.memoryReservation != null) {
         this.memoryReservation.close();
         this.memoryReservation = null;
      }
   }

   public boolean memoryThrottled() {
      return this.memoryThrottled;
   }

   public int previousBatchCells() {
      return this.batchFeedback.cells();
   }

   public long previousBatchNanos() {
      return this.batchFeedback.elapsedNanos();
   }

   public void recordBatch(int cells, long elapsedNanos) {
      this.batchFeedback.record(cells, elapsedNanos);
   }

   public String metricsSummary() {
      return this.metrics.summary();
   }

   private static WorldOperationPhase metricsPhase(Phase phase) {
      return switch (phase) {
         case CHECK, CONFLICT -> WorldOperationPhase.SNAPSHOT;
         case JOURNAL -> WorldOperationPhase.JOURNAL;
         case APPLY -> WorldOperationPhase.WRITE;
         case ROLLBACK -> WorldOperationPhase.ROLLBACK;
         case RESOLVE -> WorldOperationPhase.COMMIT;
         case FAILED -> WorldOperationPhase.FAILED;
      };
   }

   public void requestCancel() {
      this.cancelRequested = true;
      this.retainRecovery = this.recovery;
      if (this.phase == Phase.APPLY && this.applied != null && !this.applied.isEmpty()) {
         this.phase = Phase.ROLLBACK;
         this.index = this.batch.size() - 1;
      }
   }

   public boolean respondToConflict(WorldTaskContext context, UUID token,
      HistoryConflictResponsePayload.Choice choice) {
      if (this.phase != Phase.CONFLICT || conflictPrompt == null || !conflictPrompt.accepts(token) || choice == null) return false;
      clearConflictPrompt();
      if (choice == HistoryConflictResponsePayload.Choice.CANCEL) requestCancel();
      else {
         this.confirmedConflicts.or(conflicts);
         this.conflicts.clear();
         resetReplayState();
         this.conflictChoice = choice;
         this.index = 0;
         this.phase = Phase.CHECK;
      }
      return true;
   }

   public void clearConflictPrompt() {
      HistoryConflictPrompt prompt = conflictPrompt;
      conflictPrompt = null;
      if (prompt != null) {
         prompt.clear();
      }
   }

   private void blockApplyFailure(int failedIndex, boolean duringRollback) {
      this.blockedApplyIndex = failedIndex;
      this.blockedDuringRollback = duringRollback;
      this.blockedNoticeTicks = 0;
      this.phase = Phase.FAILED;
   }

   /**
    * A failed snapshot read leaves no trustworthy partial-write fingerprint.
    * Resume only when the complete live cell is provably one of the batch's
    * two states; an arbitrary third state remains blocked as an external or
    * unidentifiable partial write.
    */
   private boolean resumeBlockedApply(ServerLevel level) {
      if (this.batch == null || this.applied == null || this.blockedApplyIndex < 0) {
         return false;
      }
      int failedIndex = this.blockedApplyIndex;
      int match = this.blockedDuringRollback
         ? this.replayState.rollbackMatch(level, failedIndex)
         : this.replayState.match(level, failedIndex);
      if (match == 0) {
         return false;
      }

      this.blockedApplyIndex = -1;
      this.blockedNoticeTicks = 0;
      if (this.blockedDuringRollback) {
         this.blockedDuringRollback = false;
         this.phase = Phase.ROLLBACK;
         if (match == 2) {
            // The inverse target is already complete, so this cell no
            // longer needs another rollback attempt.
            this.applied.clear(failedIndex);
            this.index = failedIndex - 1;
         } else {
            // The operation target is still intact; retry its inverse.
            this.index = failedIndex;
         }
         return true;
      }

      // APPLY failed before its fingerprint could be captured. If its
      // target nevertheless completed, continue. If the source is intact,
      // exclude this untouched cell and atomically roll back earlier cells.
      this.blockedDuringRollback = false;
      if (match == 2) {
         this.phase = Phase.APPLY;
         this.index = failedIndex + 1;
      } else {
         this.applied.clear(failedIndex);
         this.phase = Phase.ROLLBACK;
         this.index = this.batch.size() - 1;
      }
      return true;
   }

   public boolean retainRecovery() {
      return this.retainRecovery;
   }

   public HistoryEntry batch() {
      return this.batch;
   }

   private boolean acquireLease(WorldTaskContext context) {
      return this.batch != null && writeLease.acquire(context, batch.dimension(), metrics);
   }

   public void releaseLease() {
      writeLease.release();
   }

   private void releaseAfterCancelledJournal() {
      if (journal == null) writeLease.release();
      else writeLease.releaseAfterCancellation(journal::releaseAfterCancellation);
   }

   private enum Phase {
      CHECK,
      CONFLICT,
      JOURNAL,
      APPLY,
      ROLLBACK,
      RESOLVE,
      FAILED
   }
}
