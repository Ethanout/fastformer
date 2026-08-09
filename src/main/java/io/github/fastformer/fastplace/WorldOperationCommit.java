package io.github.fastformer.fastplace;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Prepares the crash correction and compact in-memory history off-thread. */
final class WorldOperationCommit {
   private final CompletableFuture<Boolean> journalFuture;
   private final CompletableFuture<Optional<WorldChangeBatch>> batchFuture;
   private final PersistentRecoveryJournal journal;
   private volatile boolean cancelled;

   private WorldOperationCommit(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      this.journal = journal;
      this.journalFuture = journal == null
         ? CompletableFuture.completedFuture(true)
         : journal.finalizeAfter(after);
      this.batchFuture = CompletableFuture.supplyAsync(
         () -> this.cancelled
            ? Optional.empty()
            : WorldChangeBatch.capturePairsByPos(dimension, before, after),
         PersistentRecoveryJournal.executor()
      );
   }

   static WorldOperationCommit begin(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      return new WorldOperationCommit(dimension, before, after, journal);
   }

   JournalPreparation poll() {
      if (!this.journalFuture.isDone() || !this.batchFuture.isDone()) {
         return JournalPreparation.PENDING;
      }
      try {
         if (!this.journalFuture.join()) {
            return JournalPreparation.FAILED;
         }
         this.batchFuture.join();
         return JournalPreparation.READY;
      } catch (RuntimeException exception) {
         return JournalPreparation.FAILED;
      }
   }

   Optional<WorldChangeBatch> batch() {
      return this.batchFuture.join();
   }

   void cancel() {
      this.cancelled = true;
      if (this.journal != null) {
         this.journalFuture.whenComplete((ignored, exception) -> this.journal.deleteOrphanCorrection());
      }
   }
}
