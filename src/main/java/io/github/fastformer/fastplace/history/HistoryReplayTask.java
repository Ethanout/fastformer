package io.github.fastformer.fastplace.history;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.recovery.JournalPreparation;
import io.github.fastformer.fastplace.recovery.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.recovery.WorldJournalPreparation;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.world.WorldBatchFeedback;
import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import io.github.fastformer.fastplace.world.WorldOperationPhase;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.memory.MemoryAdmission;
import io.github.fastformer.fastplace.world.memory.MemoryReservation;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.BitSet;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/** Executes one undo, redo, or recovery sequence while retaining its write lease. */
final class HistoryReplayTask {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final HistoryAccess historyAccess;

   enum PageState { WAITING, FAILED, EXHAUSTED }

   interface HistoryAccess {
      PageState requestPage(WorldTaskContext context, boolean undo, int remaining);
      void commit(WorldTaskContext context, HistoryMemoryCache history, boolean undo);
   }

   boolean recovery() {
      return recovery;
   }

   private final HistoryMemoryCache history;
   private final boolean undo;
   private final int requested;
   private final boolean recovery;
   private final PlacementUpdateMode updateMode;
   private PersistentRecoveryJournal journal;
   private final WorldJournalPreparation journalPreparation = new WorldJournalPreparation();
   private WorldChangeBatch batch;
   private int completed;
   private int index;
   private Phase phase = Phase.CHECK;
   private BitSet applied;
   private boolean cancelRequested;
   private boolean dimensionNoticeSent;
   private boolean retainRecovery;
   private int skippedConflicts;
   private int durabilityRetryTicks;
   private WorldWriteCoordinator.Lease leased;
   private int partialApplyIndex = -1;
   private ReversibleBlockSnapshot partialApplyState;
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
   private UUID conflictToken;
   private io.github.fastformer.network.payload.world.HistoryConflictResponsePayload.Choice conflictChoice;
   private HistoryConflictResolution conflictResolution = new HistoryConflictResolution();
   private int conflictReminderTicks;
   private int conflictDisplayIndex;
   private ResourceKey<Level> conflictPlayerDimension;

   HistoryReplayTask(
      HistoryAccess historyAccess, HistoryMemoryCache history, boolean undo, int requested, PlacementUpdateMode updateMode
   ) {
      this.historyAccess = historyAccess;
      this.history = history;
      this.undo = undo;
      this.requested = requested;
      this.recovery = false;
      this.updateMode = HistoryRecoveryPolicy.effectiveUpdateMode(updateMode, false);
      this.journal = null;
      WorldChangeBatch first = history == null
         ? null
         : history.peek(undo);
      this.operationId = first == null || first.operationId() == null
         ? UUID.randomUUID()
         : first.operationId();
      this.metrics = new WorldOperationMetrics(this.operationId);
      this.metrics.queued();
      this.batchFeedback = new WorldBatchFeedback(this.metrics);
   }

   private HistoryReplayTask(HistoryAccess historyAccess, WorldChangeBatch batch, PersistentRecoveryJournal journal) {
      this.historyAccess = historyAccess;
      this.history = null;
      this.undo = true;
      this.requested = 1;
      this.recovery = true;
      this.updateMode = HistoryRecoveryPolicy.effectiveUpdateMode(null, true);
      this.batch = batch;
      this.journal = journal;
      this.operationId = journal != null && journal.operationId() != null
         ? journal.operationId()
         : batch != null && batch.operationId() != null ? batch.operationId() : UUID.randomUUID();
      this.metrics = new WorldOperationMetrics(this.operationId);
      this.metrics.queued();
      this.batchFeedback = new WorldBatchFeedback(this.metrics);
   }

   static HistoryReplayTask recovery(HistoryAccess historyAccess, WorldChangeBatch batch, PersistentRecoveryJournal journal) {
      return new HistoryReplayTask(historyAccess, batch, journal);
   }

   UUID operationId() {
      return this.operationId;
   }

   boolean completedSuccessfully() {
      return this.completedSuccessfully;
   }

   void markMetricsComplete() {
      this.metrics.complete();
   }

   /** Returns true once the caller can retire this task. */
   boolean tick(WorldTaskContext context, WorldTaskBudget budget) {
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
            clearConflictPrompt(context);
            this.cancelJournalPreparation();
            if (!this.recovery) {
               this.releaseAfterCancelledJournal(context);
            }
            context.actionBar(FastPlaceMessages.text(
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
               context.actionBar(FastPlaceMessages.text("fastformer.message.history_dimension_failed"));
               this.dimensionNoticeSent = true;
            }
            return false;
         }
         this.dimensionNoticeSent = false;
         if (this.phase == Phase.CONFLICT) {
            var player = context.onlinePlayer();
            if (player == null || !player.level().dimension().equals(conflictPlayerDimension)) {
               clearConflictPrompt(context);
               return true;
            }
            sendConflictHighlights(context);
            if (conflictReminderTicks-- <= 0) {
               context.actionBar(FastPlaceMessages.text("fastformer.message.history_conflict_confirm", conflicts.cardinality()));
               conflictReminderTicks = 40;
            }
            return false;
         }
         if (!this.ensureMemoryReservation()) {
            context.actionBar(FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
            return false;
         }
         if (!this.acquireLease(context)) {
            context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
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
         context.actionBar(FastPlaceMessages.text("fastformer.message.history_conflict"));
         return Step.FINISHED;
      }
      if (this.batch == null) {
         this.batch = this.history.peek(this.undo);
         if (this.batch == null) {
            if (!this.recovery) {
               this.releaseLease(context);
               if (this.cancelRequested) {
                  context.actionBar(FastPlaceMessages.text("fastformer.message.cancelled"));
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
                  FastPlaceMessages.text(
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
         this.conflictResolution = new HistoryConflictResolution();
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
         context.actionBar(FastPlaceMessages.text("fastformer.message.task_recovery_blocked"));
      }
      return Step.YIELD;
   }

   private Step resolveJournal(WorldTaskContext context) {
      if (this.durabilityRetryTicks > 0) {
         this.durabilityRetryTicks--;
         return Step.YIELD;
      }
      if (this.journal != null && !this.journal.resolveAfterRollback(context.server())) {
         this.durabilityRetryTicks = 100;
         context.actionBar(FastPlaceMessages.text("fastformer.message.restore_failed_retry"));
         return Step.YIELD;
      }
      this.releaseLease(context);
      this.journal = null;
      this.journalPreparation.reset();
      if (this.recovery && this.skippedConflicts > 0) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.restore_complete_conflicts", this.skippedConflicts));
      } else {
         context.actionBar(
            FastPlaceMessages.text(
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
               boolean skip = conflictChoice == io.github.fastformer.network.payload.world.HistoryConflictResponsePayload.Choice.SKIP;
               if (!conflictResolution.capture(level, batch, index, skip)) {
                  context.actionBar(FastPlaceMessages.text("fastformer.message.history_dimension_failed"));
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
         this.releaseLease(context);
         this.conflictResolution = new HistoryConflictResolution();
         this.releaseMemoryReservation();
         this.conflictToken = UUID.randomUUID();
         var player = context.onlinePlayer();
         if (player == null || !player.connection.hasChannel(io.github.fastformer.network.payload.world.HistoryConflictPayload.TYPE)) return Step.FINISHED;
         conflictPlayerDimension = player.level().dimension();
         conflictDisplayIndex = 0;
         sendConflictHighlights(context);
         context.actionBar(FastPlaceMessages.text("fastformer.message.history_conflict_confirm", conflicts.cardinality()));
         conflictReminderTicks = 40;
         return Step.YIELD;
      }
      this.applied = new BitSet(this.batch.size());
      this.phase = Phase.JOURNAL;
      return Step.NEXT;
   }

   private Step prepareBatchJournal(WorldTaskContext context) {
      JournalPreparation preparation = this.prepareJournal(context);
      if (preparation == JournalPreparation.PENDING) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_preparing"));
         return Step.YIELD;
      }
      if (preparation == JournalPreparation.FAILED) {
         this.releaseLease(context);
         this.metrics.phase(WorldOperationPhase.FAILED);
         this.retainRecovery = this.recovery;
         LOGGER.error(
            "FastFormer recovery journal preparation failed operation={} phase={} metrics={}",
            this.operationId,
            this.phase,
            this.metrics.summary()
         );
         context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_failed"));
         return Step.FINISHED;
      }
      this.phase = Phase.APPLY;
      return Step.NEXT;
   }

   private Step applyBatch(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      while (this.index < this.batch.size() && budget.tryConsume()) {
         int match = this.conflictResolution.match(level, this.batch, this.index, this.undo);
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
            Optional<ReversibleBlockSnapshot> partial = ReversibleBlockSnapshot.capture(
               level, this.batch.position(this.index)
            );
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
            ? io.github.fastformer.network.payload.world.WorldHistoryEventPayload.Kind.UNDO
            : io.github.fastformer.network.payload.world.WorldHistoryEventPayload.Kind.REDO,
            !this.conflictResolution.hasSkippedCells());
         this.journal = null;
         this.journalPreparation.reset();
      }
      this.completed++;
      if (this.recovery || this.completed >= this.requested) {
         if (this.recovery) {
            this.phase = Phase.RESOLVE;
            this.durabilityRetryTicks = 0;
            return Step.NEXT;
         }
         this.releaseLease(context);
         this.completedSuccessfully = true;
         context.actionBar(
            FastPlaceMessages.text(
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
            && this.partialApplyState.matches(level, this.batch.position(appliedIndex));
         boolean normalTargetMatches = this.batch.matchesExpected(level, appliedIndex, inverseUndo);
         if (HistoryRecoveryPolicy.ownsPartialRollback(
            partialIndexMatches, partialFingerprintMatches, normalTargetMatches
         )) {
            boolean rolledBack = this.conflictResolution.rollback(level, this.batch, appliedIndex, this.undo, PlacementUpdateMode.CLIENT_ONLY.flags());
            this.metrics.writeAttempt(rolledBack);
            if (!rolledBack) {
               Optional<ReversibleBlockSnapshot> partial = ReversibleBlockSnapshot.capture(
                  level, this.batch.position(appliedIndex)
               );
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
               context.actionBar(FastPlaceMessages.text("fastformer.message.restore_failed_retry"));
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
         context.actionBar(FastPlaceMessages.text("fastformer.message.task_recovery_blocked"));
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
            FastPlaceMessages.text(
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
         context.actionBar(FastPlaceMessages.text(
            "fastformer.message.recovery_progress", processed, this.batch.size()
         ));
      } else {
         context.actionBar(FastPlaceMessages.text(
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
      if (this.recovery || this.journal != null) {
         return JournalPreparation.READY;
      }
      JournalPreparation preparation = this.journalPreparation.poll(() -> PersistentRecoveryJournal.begin(
            context.server(),
            context.owner(),
            this.batch.dimension(),
            this.conflictResolution.snapshots(this.batch, this.undo, false),
            this.conflictResolution.snapshots(this.batch, this.undo, true),
            this.operationId
         ));
      if (preparation == JournalPreparation.PENDING) {
         return preparation;
      }
      if (preparation == JournalPreparation.FAILED) {
         this.metrics.phase(WorldOperationPhase.JOURNAL);
         LOGGER.error(
            "FastFormer history journal preparation failed operation={} phase={} reason={}",
            this.operationId,
            this.phase,
            this.journalPreparation.failureReason()
         );
         return preparation;
      }
      this.journal = this.journalPreparation.journal();
      return preparation;
   }

   void cancelJournalPreparation() {
      this.journalPreparation.cancel();
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

   void releaseMemoryReservation() {
      if (this.memoryReservation != null) {
         this.memoryReservation.close();
         this.memoryReservation = null;
      }
   }

   boolean memoryThrottled() {
      return this.memoryThrottled;
   }

   int previousBatchCells() {
      return this.batchFeedback.cells();
   }

   long previousBatchNanos() {
      return this.batchFeedback.elapsedNanos();
   }

   void recordBatch(int cells, long elapsedNanos) {
      this.batchFeedback.record(cells, elapsedNanos);
   }

   String metricsSummary() {
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

   void requestCancel() {
      this.cancelRequested = true;
      this.retainRecovery = this.recovery;
      if (this.phase == Phase.APPLY && this.applied != null && !this.applied.isEmpty()) {
         this.phase = Phase.ROLLBACK;
         this.index = this.batch.size() - 1;
      }
   }

   boolean respondToConflict(WorldTaskContext context, UUID token,
      io.github.fastformer.network.payload.world.HistoryConflictResponsePayload.Choice choice) {
      if (this.phase != Phase.CONFLICT || !java.util.Objects.equals(token, conflictToken) || token == null) return false;
      clearConflictPrompt(context);
      if (choice == io.github.fastformer.network.payload.world.HistoryConflictResponsePayload.Choice.CANCEL) requestCancel();
      else {
         this.confirmedConflicts.or(conflicts);
         this.conflicts.clear();
         this.conflictResolution = new HistoryConflictResolution();
         this.conflictChoice = choice;
         this.index = 0;
         this.phase = Phase.CHECK;
      }
      return true;
   }

   void clearConflictPrompt(WorldTaskContext context) {
      if (conflictToken == null) return;
      var player = context.onlinePlayer();
      if (player != null && player.connection.hasChannel(io.github.fastformer.network.payload.world.HistoryConflictPayload.TYPE))
         net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new io.github.fastformer.network.payload.world.HistoryConflictPayload(conflictToken, batch.dimension().location(), 0, java.util.List.of()));
      conflictToken = null;
   }

   private void sendConflictHighlights(WorldTaskContext context) {
      if (conflictToken == null || conflictDisplayIndex < 0) return;
      var player = context.onlinePlayer();
      if (player == null) return;
      var positions = new java.util.ArrayList<net.minecraft.core.BlockPos>();
      int next = conflicts.nextSetBit(conflictDisplayIndex);
      while (next >= 0 && positions.size() < io.github.fastformer.network.payload.world.HistoryConflictPayload.MAX_POSITIONS) {
         positions.add(batch.position(next));
         next = conflicts.nextSetBit(next + 1);
      }
      conflictDisplayIndex = next;
      if (!positions.isEmpty()) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
         new io.github.fastformer.network.payload.world.HistoryConflictPayload(conflictToken, batch.dimension().location(), conflicts.cardinality(), positions));
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
         ? this.conflictResolution.rollbackMatch(level, batch, failedIndex, undo)
         : this.conflictResolution.match(level, batch, failedIndex, undo);
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

   boolean retainRecovery() {
      return this.retainRecovery;
   }

   WorldChangeBatch batch() {
      return this.batch;
   }

   PersistentRecoveryJournal journal() {
      return this.journal;
   }

   private boolean acquireLease(WorldTaskContext context) {
      if (this.batch == null) {
         return false;
      }
      ResourceKey<Level> desired = this.batch.dimension();
      if (this.leased != null && desired.equals(this.leased.dimension()) && WorldWriteCoordinator.renew(this.leased)) {
         return true;
      }
      if (this.leased != null) {
         WorldWriteCoordinator.release(this.leased);
         this.leased = null;
      }
      // A recovery or the next history step takes over the lease of the same
      // owner. The takeover keeps the lease held and makes the predecessor's
      // late release a no-op, so no other writer can enter between them.
      this.leased = WorldWriteCoordinator.takeOver(context.server(), desired, context.owner());
      if (this.leased == null) {
         return false;
      }
      this.metrics.leaseAcquired();
      return true;
   }

   void releaseLease(WorldTaskContext context) {
      if (this.leased != null) {
         WorldWriteCoordinator.release(this.leased);
         this.leased = null;
      }
   }

   private void releaseAfterCancelledJournal(WorldTaskContext context) {
      WorldWriteCoordinator.Lease cancelled = this.leased;
      this.leased = null;
      if (cancelled == null) {
         // This task never took the lease, so it must not release anything.
         // An owner-keyed release here could free the transaction of the same
         // player that still holds the dimension.
         return;
      }
      WorldWriteCoordinator.releaseAfterUnusedJournal(cancelled, this.journal, this.journalPreparation.future());
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
