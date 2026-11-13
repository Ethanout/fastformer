package io.github.fastformer.server.history.replay;

import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.server.recovery.JournalPreparation;
import java.util.UUID;

/** Durability and cancellation boundary supplied by an entry's replay implementation. */
public interface ReplayJournal {
   JournalPreparation prepare(WorldTaskContext context, UUID operationId);
   boolean complete();
   boolean resolve(WorldTaskContext context);
   void cancelPreparation();
   void releaseAfterCancellation(WorldWriteCoordinator.Lease lease);
   String failureReason();
}
