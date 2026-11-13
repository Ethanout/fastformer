package io.github.fastformer.server.history.replay;

import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import java.util.UUID;

/** Adapts the block recovery log to the entry-neutral replay lifecycle. */
public final class BlockReplayJournal implements ReplayJournal {
   private final WorldJournalPreparation preparation = new WorldJournalPreparation();
   private final WorldChangeBatch batch;
   private final boolean undo;
   private final HistoryConflictResolution conflicts;
   private final boolean recovery;
   private PersistentRecoveryJournal journal;

   BlockReplayJournal(WorldChangeBatch batch, boolean undo, HistoryConflictResolution conflicts) {
      this.batch = batch;
      this.undo = undo;
      this.conflicts = conflicts;
      this.recovery = false;
   }

   private BlockReplayJournal(PersistentRecoveryJournal journal) {
      this.batch = null;
      this.undo = true;
      this.conflicts = null;
      this.recovery = true;
      this.journal = journal;
   }

   public static ReplayJournal recovery(PersistentRecoveryJournal journal) {
      return new BlockReplayJournal(journal);
   }

   @Override public JournalPreparation prepare(WorldTaskContext context, UUID operationId) {
      if (recovery || journal != null) return JournalPreparation.READY;
      JournalPreparation result = preparation.poll(() -> context.recovery().begin(
         context.owner(), batch.dimension(), conflicts.snapshots(batch, undo, false),
         conflicts.snapshots(batch, undo, true), operationId), context);
      if (result == JournalPreparation.READY) journal = preparation.journal();
      return result;
   }

   @Override public boolean complete() { return journal == null || journal.complete(); }
   @Override public boolean resolve(WorldTaskContext context) {
      return journal == null || journal.resolveAfterRollback(context.server());
   }
   @Override public void cancelPreparation() { preparation.cancel(); }
   @Override public void releaseAfterCancellation(WorldWriteCoordinator.Lease lease) {
      preparation.releaseAfterCancellation(lease);
   }
   @Override public String failureReason() { return preparation.failureReason(); }
}
