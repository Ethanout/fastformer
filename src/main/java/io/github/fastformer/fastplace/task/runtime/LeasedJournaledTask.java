package io.github.fastformer.fastplace.task.runtime;

import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.CancelReason;
import io.github.fastformer.fastplace.task.MemoryReservationAttempt;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldBatchFeedback;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import io.github.fastformer.fastplace.world.WorldOperationPhase;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.memory.MemoryReservation;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;

/** Owns the resources shared by every journaled block writer. Only task algorithms vary. */
public abstract class LeasedJournaledTask implements WorldTask {
   protected final WorldOperationMetrics metrics = new WorldOperationMetrics();
   protected final WorldChangeTransaction transaction = new WorldChangeTransaction();
   protected final JournalSegments journalSegments = new JournalSegments();
   protected final WorldJournalPreparation journalPreparation;
   protected WorldOperationCommit operationCommit;
   protected MemoryReservation memoryReservation;
   protected boolean memoryThrottled;
   private final WorldBatchFeedback batchFeedback = new WorldBatchFeedback(metrics);
   private final TaskWriteLease writeLease = new TaskWriteLease();
   private TaskStep terminalStep;
   private boolean stopped;

   protected LeasedJournaledTask(WorldJournalPreparation journalPreparation) {
      this.journalPreparation = Objects.requireNonNull(journalPreparation, "journalPreparation");
   }

   /** Shared admission and batch accounting; subclasses own only their algorithm. */
   @Override
   public final TaskStep tick(WorldTaskContext context) {
      if (stopped) return TaskStep.CANCELLED;
      if (terminalStep != null) return terminalStep;
      if (!context.writesAllowed()) return remember(TaskStep.BLOCKED);
      TaskStep preparation = prepareTick(context);
      if (preparation != TaskStep.ACTIVE) return remember(preparation);
      ServerLevel level = context.level(dimension());
      if (level == null) {
         markWorldUnloaded();
         releaseMemoryReservation();
         return TaskStep.WORLD_UNLOADED;
      }
      MemoryReservationAttempt reservation = reserveWorkingSet();
      if (reservation == MemoryReservationAttempt.REJECTED) return remember(TaskStep.MEMORY_UNSAFE);
      if (reservation == MemoryReservationAttempt.RETRY || !acquireLease(context)) return TaskStep.WAITING;
      return tickBatch(context, level, createBudget());
   }

   /** Package-local batch boundary also used by deterministic task fixtures. */
   final TaskStep tickBatch(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      if (stopped) return TaskStep.CANCELLED;
      if (terminalStep != null) return terminalStep;
      long startedAt = System.nanoTime();
      try {
         return remember(tickBody(context, level, budget));
      } finally {
         recordBatch(budget.consumed(), System.nanoTime() - startedAt);
      }
   }

   private TaskStep remember(TaskStep step) {
      Objects.requireNonNull(step, "task step");
      if (step.terminal()) terminalStep = step;
      return step;
   }

   protected TaskStep prepareTick(WorldTaskContext context) { return TaskStep.ACTIVE; }

   protected WorldTaskBudget createBudget() {
      return WorldTaskBudget.forServerTick(memoryThrottled(), previousBatchCells(), previousBatchNanos());
   }

   public MemoryReservationAttempt reserveWorkingSet() { return MemoryReservationAttempt.ACQUIRED; }

   protected abstract TaskStep tickBody(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget);

   public String phaseName() { return metrics.phase().name(); }

   public final PersistentRecoveryJournal journal() { return journalPreparation.journal(); }
   public final WorldChangeTransaction transaction() { return transaction; }
   public final WorldOperationCommit operationCommit() { return operationCommit; }
   public final boolean hasWrites() { return transaction.hasWrites(); }
   public final CompletableFuture<Void> journalCompletion() { return journalPreparation.completion(); }

   public final boolean acquireLease(WorldTaskContext context) {
      return writeLease.acquire(context, dimension(), metrics);
   }

   public final void releaseLease(WorldTaskContext context) {
      try { releaseAttachedResources(); }
      finally { writeLease.release(); }
   }

   /** Transfer the original lease before asynchronous cleanup can observe a successor. */
   public final void releaseAfterCancelledJournal(WorldTaskContext context) {
      try { releaseAttachedResources(); }
      finally { writeLease.releaseAfterCancellation(journalPreparation, operationCommit); }
   }

   @Override
   public final void cancel(CancelReason reason) {
      Objects.requireNonNull(reason, "reason");
      stopped = true;
      try {
         try { stopTaskActivity(); }
         finally { journalPreparation.cancel(); }
      } finally {
         if (reason == CancelReason.SHUTDOWN) releaseMemoryReservation();
      }
   }

   public final WorldRecoverySnapshot stopAndTransferRecovery() {
      cancel(CancelReason.RECOVERY);
      CompletableFuture<Void> publication = operationCommit == null
         ? CompletableFuture.completedFuture(null) : operationCommit.stopForRecovery();
      return transaction.transferRecoverySnapshot(CompletableFuture.allOf(publication, journalCompletion()));
   }

   /** Pending journal/publication work retains the budget until it stops using the snapshots. */
   public final void releaseMemoryReservation() {
      try { releaseAttachedResources(); }
      finally {
         MemoryReservation reservation = memoryReservation;
         memoryReservation = null;
         if (reservation != null) {
            CompletableFuture<?> publication = operationCommit == null
               ? CompletableFuture.completedFuture(null) : operationCommit.completion();
            journalPreparation.releaseWhenIdle(reservation, publication);
         }
      }
   }

   /** Called only after the durable commit succeeds; recovery paths retain their snapshots. */
   public final void releaseCommittedTransactionState() {
      try { releaseAttachedResources(); }
      finally {
         transaction.releaseWriteState();
         operationCommit = null;
         clearCommittedStaging();
      }
   }

   public final Optional<WorldChangeBatch> preparedBatch() {
      return operationCommit == null ? Optional.empty() : operationCommit.batch().map(batch -> batch.withOperationId(operationId()));
   }

   public final UUID operationId() { return metrics.operationId(); }
   public String metricsSummary() { return metrics.summary(); }
   public final void markWorldUnloaded() { metrics.phase(WorldOperationPhase.WORLD_UNLOADED); }
   public final void markComplete() { metrics.complete(); }
   public final boolean memoryThrottled() { return memoryThrottled; }
   public final int previousBatchCells() { return batchFeedback.cells(); }
   public final long previousBatchNanos() { return batchFeedback.elapsedNanos(); }
   public final void recordBatch(int cells, long elapsedNanos) { batchFeedback.record(cells, elapsedNanos); }

   /** Stops generation or other task-owned asynchronous input before recovery extraction. */
   protected void stopTaskActivity() {}

   /** Releases task-specific resources such as a workspace tick barrier. Must be idempotent. */
   protected void releaseAttachedResources() {}

   protected void clearCommittedStaging() {}
}
