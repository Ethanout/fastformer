package io.github.fastformer.server.task;

import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** A server-owned writer retaining its resources until durable commit or accepted recovery. */
public interface WorldTask {
   TaskStep tick(WorldTaskContext context);
   void cancel(CancelReason reason);
   ResourceKey<Level> dimension();
   String phaseName();
   UUID operationId();
   String metricsSummary();
   default boolean hasWrites() { return transaction().hasWrites(); }
   WorldChangeTransaction transaction();
   PersistentRecoveryJournal journal();
   Optional<WorldChangeBatch> preparedBatch();
   WorldRecoverySnapshot stopAndTransferRecovery();
   boolean acquireLease(WorldTaskContext context);
   void releaseLease(WorldTaskContext context);
   void releaseAfterCancelledJournal(WorldTaskContext context);
   void releaseMemoryReservation();
   void releaseCommittedTransactionState();
   void markComplete();
}
