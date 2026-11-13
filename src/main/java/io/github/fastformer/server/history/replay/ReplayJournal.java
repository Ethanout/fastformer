package io.github.fastformer.server.history.replay;

import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.worldwrite.WorldWriteLeases;
import io.github.fastformer.server.recovery.JournalPreparation;
import java.util.UUID;

/** Durability and cancellation boundary supplied by an entry's replay implementation. */
public interface ReplayJournal {
   JournalPreparation prepare(WorldTaskContext context, UUID operationId);
   boolean complete();
   boolean resolve(WorldTaskContext context);
   void cancelPreparation();
   void releaseAfterCancellation(WorldWriteLeases.Lease lease);
   String failureReason();
}
