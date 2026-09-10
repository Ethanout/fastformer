package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.world.WorldChangeBatch;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.UUID;
import java.util.Optional;
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

   default boolean hasWrites() {
      return transaction().hasWrites();
   }

   /** Stops task-owned preparation and transfers recovery storage once. */
   default WorldRecoverySnapshot stopAndTransferRecovery() {
      cancelJournalPreparation();
      return transaction().transferRecoverySnapshot();
   }

   ResourceKey<Level> dimension();

   default Optional<WorldChangeBatch> preparedBatch() {
      return transaction().preparedBatch(operationId());
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
   default boolean ensureMemoryReservation() {
      return true;
   }

   default void recordBatch(int cells, long elapsedNanos) {
   }

   default void releaseMemoryReservation() {
   }

   default void releaseCommittedTransactionState() {
   }
}
