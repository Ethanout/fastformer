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
      if (this.cancelled) return JournalPreparation.FAILED;
      if (this.journal != null) return JournalPreparation.READY;
      if (this.future == null) {
         this.future = CompletableFuture.supplyAsync(factory, this.executor);
         // A direct or very fast executor may complete before the scheduling
         // call returns. Consume that result now so the writer does not incur
         // an avoidable extra server tick before its first block.
         if (!this.future.isDone()) {
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

   public boolean started() { return this.future != null; }
   public PersistentRecoveryJournal journal() { return this.journal; }
   public CompletableFuture<Optional<PersistentRecoveryJournal>> future() { return this.future; }
   public String failureReason() {
      return this.failureReason == null ? "journal preparation failed" : this.failureReason;
   }

   public void reset() {
      this.future = null;
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
