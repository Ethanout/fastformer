package io.github.fastformer.fastplace.world;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Owns asynchronous journal creation and late-completion cleanup. */
public final class WorldJournalPreparation {
   private final Executor executor;
   private CompletableFuture<Optional<PersistentRecoveryJournal>> future;
   private CompletableFuture<Boolean> appendFuture;
   private PersistentRecoveryJournal journal;
   private volatile boolean cancelled;
   private String failureReason;

   public WorldJournalPreparation() {
      this(PersistentRecoveryJournal.executor());
   }

   public WorldJournalPreparation(Executor executor) {
      this.executor = executor;
   }

   public JournalPreparation poll(Supplier<Optional<PersistentRecoveryJournal>> factory) {
      return poll(factory, null);
   }

   public JournalPreparation poll(Supplier<Optional<PersistentRecoveryJournal>> factory, WorldTaskContext context) {
      if (this.cancelled) return JournalPreparation.FAILED;
      if (this.journal != null) return JournalPreparation.READY;
      if (this.future == null) {
         this.future = CompletableFuture.supplyAsync(factory, this.executor);
         // A direct or very fast executor may complete before the scheduling
         // call returns. Consume that result now so the writer does not incur
         // an avoidable extra server tick before its first block.
         if (!this.future.isDone()) {
            if (context != null) {
               CompletableFuture<?> started = this.future;
               context.resumeAfter(started, () -> !this.cancelled && this.future == started && this.journal == null);
            }
            return JournalPreparation.PENDING;
         }
      }
      if (!this.future.isDone()) return JournalPreparation.PENDING;
      try {
         Optional<PersistentRecoveryJournal> created = this.future.join();
         if (this.cancelled) {
            created.ifPresent(PersistentRecoveryJournal::discardUnused);
            this.failureReason = "journal preparation cancelled";
            return JournalPreparation.FAILED;
         }
         this.journal = created.orElse(null);
      } catch (RuntimeException | OutOfMemoryError exception) {
         Throwable cause = exception.getCause() == null ? exception : exception.getCause();
         this.failureReason = cause.getClass().getSimpleName();
         return JournalPreparation.FAILED;
      }
      if (this.journal == null) {
         this.failureReason = "journal creation returned empty";
         return JournalPreparation.FAILED;
      }
      return JournalPreparation.READY;
   }

   public JournalPreparation pollAppend(Supplier<Boolean> appender) {
      if (this.cancelled) return JournalPreparation.FAILED;
      if (this.journal == null) {
         this.failureReason = "journal append before create";
         return JournalPreparation.FAILED;
      }
      if (this.appendFuture == null) {
         this.appendFuture = CompletableFuture.supplyAsync(appender, this.executor);
         if (!this.appendFuture.isDone()) {
            return JournalPreparation.PENDING;
         }
      }
      if (!this.appendFuture.isDone()) return JournalPreparation.PENDING;
      try {
         boolean written = Boolean.TRUE.equals(this.appendFuture.join());
         this.appendFuture = null;
         if (this.cancelled) {
            this.failureReason = "journal preparation cancelled";
            return JournalPreparation.FAILED;
         }
         if (!written) {
            this.failureReason = "journal append returned empty";
            return JournalPreparation.FAILED;
         }
         return JournalPreparation.READY;
      } catch (RuntimeException | OutOfMemoryError exception) {
         this.appendFuture = null;
         Throwable cause = exception.getCause() == null ? exception : exception.getCause();
         this.failureReason = cause.getClass().getSimpleName();
         return JournalPreparation.FAILED;
      }
   }

   public boolean started() { return this.future != null; }
   public PersistentRecoveryJournal journal() { return this.journal; }
   public CompletableFuture<Optional<PersistentRecoveryJournal>> future() { return this.future; }
   public String failureReason() {
      return this.failureReason == null ? "journal preparation failed" : this.failureReason;
   }

   /** Recovery must wait until pending I/O can no longer append or create journal files. */
   public CompletableFuture<Void> completion() {
      CompletableFuture<?> pending = this.appendFuture != null ? this.appendFuture : this.future;
      return pending == null ? CompletableFuture.completedFuture(null) : pending.handle((ignored, failure) -> null);
   }

   /** Releases a working-set reservation after the current journal I/O stops using it. */
   public void releaseWhenIdle(MemoryReservation reservation) {
      releaseWhenIdle(reservation, CompletableFuture.completedFuture(null));
   }

   /** Releases a reservation after journal I/O and its dependent publication terminate. */
   public void releaseWhenIdle(MemoryReservation reservation, CompletableFuture<?> publicationCompletion) {
      if (reservation == null) return;
      CompletableFuture<?> journalCompletion = this.appendFuture != null ? this.appendFuture : this.future;
      CompletableFuture<?> pending = journalCompletion == null
         ? publicationCompletion
         : CompletableFuture.allOf(journalCompletion, publicationCompletion);
      if (pending.isDone()) {
         reservation.close();
         return;
      }
      pending.whenComplete((ignored, exception) -> reservation.close());
   }

   public void reset() {
      this.future = null;
      this.appendFuture = null;
      this.journal = null;
      this.cancelled = false;
      this.failureReason = null;
   }

   public void cancel() {
      if (this.cancelled) return;
      this.cancelled = true;
      this.failureReason = "journal preparation cancelled";
      if (this.future == null || this.journal != null) return;
      this.future.whenComplete((created, exception) -> {
         if (exception == null && created != null) {
            created.ifPresent(PersistentRecoveryJournal::discardUnused);
         }
      });
   }

   public void releaseAfterCancellation(WorldTaskContext context, ResourceKey<Level> dimension) {
      cancel();
      WorldWriteCoordinator.releaseAfterUnusedJournal(
         context.server(), dimension, context.owner(), this.journal, this.future
      );
   }
}
