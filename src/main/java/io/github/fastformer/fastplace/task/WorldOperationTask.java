package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.world.WorldChangeBatch;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** A UUID-owned server task that validates and atomically changes one world. */
public interface WorldOperationTask {
   OperationTaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget);

   String phaseName();

   void cancelJournalPreparation();

   PersistentRecoveryJournal journal();

   boolean acquireLease(WorldTaskContext context);

   void releaseLease(WorldTaskContext context);

   void releaseAfterCancelledJournal(WorldTaskContext context);

   WorldChangeTransaction transaction();

   WorldOperationCommit operationCommit();

   default CompletableFuture<Void> journalCompletion() {
      return CompletableFuture.completedFuture(null);
   }

   default boolean hasWrites() {
      return transaction().hasWrites();
   }

   /** Stops task-owned preparation and transfers recovery storage once. */
   default WorldRecoverySnapshot stopAndTransferRecovery() {
      cancelJournalPreparation();
      WorldOperationCommit commit = operationCommit();
      CompletableFuture<Void> ready = commit == null ? CompletableFuture.completedFuture(null) : commit.stopForRecovery();
      return transaction().transferRecoverySnapshot(CompletableFuture.allOf(ready, journalCompletion()));
   }

   ResourceKey<Level> dimension();

   default Optional<WorldChangeBatch> preparedBatch() {
      WorldOperationCommit commit = operationCommit();
      return commit == null ? Optional.empty() : commit.batch().map(batch -> batch.withOperationId(operationId()));
   }

   UUID operationId();

   String metricsSummary();

   void markWorldUnloaded();

   void markComplete();

   default boolean memoryThrottled() {
      return false;
   }

   default int previousBatchCells() {
      return 0;
   }

   default long previousBatchNanos() {
      return 0L;
   }

   /** Re-establishes a released working-set reservation after a world reload. */
   default MemoryReservationAttempt reserveWorkingSet() {
      return MemoryReservationAttempt.ACQUIRED;
   }

   default void recordBatch(int cells, long elapsedNanos) {
   }

   default void releaseMemoryReservation() {
   }

   default void releaseCommittedTransactionState() {
   }
}
