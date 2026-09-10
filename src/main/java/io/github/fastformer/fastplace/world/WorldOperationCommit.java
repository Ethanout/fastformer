package io.github.fastformer.fastplace.world;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Prepares the crash correction and compact in-memory history off-thread. */
public final class WorldOperationCommit {
   private final CompletableFuture<Boolean> journalFuture;
   private final WorldHistoryPublication history;
   private final PersistentRecoveryJournal journal;
   private volatile boolean cancelled;
   private volatile String failureReason;

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
      this.history = WorldHistoryPublication.afterJournal(
         this.journalFuture,
         dimension,
         before,
         after,
         () -> this.cancelled
      );
   }

   public static WorldOperationCommit begin(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      return new WorldOperationCommit(dimension, before, after, journal);
   }

   static CompletableFuture<Optional<WorldChangeBatch>> prepareBatchAfterJournal(
      CompletableFuture<Boolean> journalFuture,
      java.util.function.Supplier<Optional<WorldChangeBatch>> batchSupplier,
      BooleanSupplier cancelled
   ) {
      return WorldHistoryPublication.prepare(journalFuture, batchSupplier, cancelled);
   }

   public JournalPreparation poll() {
      if (!this.journalFuture.isDone() || this.history.poll() == JournalPreparation.PENDING) {
         return JournalPreparation.PENDING;
      }
      try {
         if (!this.journalFuture.join()) {
            this.failureReason = "journal finalization returned false";
            return JournalPreparation.FAILED;
         }
         if (this.history.batch().isEmpty()) {
            this.failureReason = "history publication returned empty";
            return JournalPreparation.FAILED;
         }
         return JournalPreparation.READY;
      } catch (RuntimeException exception) {
         Throwable cause = exception.getCause() == null ? exception : exception.getCause();
         this.failureReason = cause.getClass().getSimpleName();
         return JournalPreparation.FAILED;
      }
   }

   /** Returns a concise reason when asynchronous commit preparation failed. */
   public String failureReason() {
      return this.failureReason == null ? "commit preparation failed" : this.failureReason;
   }

   public Optional<WorldChangeBatch> batch() {
      return this.history.batch();
   }

   public void cancel() {
      this.cancelled = true;
      // The batch stage can already be reading the transaction containers.
      // Always wait for it, even when this operation has no journal. Otherwise
      // cancellation can release staging while compression is still running.
      this.history.stopForRecovery().whenComplete((ignored, exception) -> {
         if (this.journal != null) {
            this.journal.deleteOrphanCorrection();
         }
      });
   }

   /**
    * Stops batch publication for a failed write but preserves its correction
    * journal. Recovery can read the transaction containers after the returned
    * signal completes.
    */
   public CompletableFuture<Void> stopForRecovery() {
      this.cancelled = true;
      return this.history.stopForRecovery();
   }

}
