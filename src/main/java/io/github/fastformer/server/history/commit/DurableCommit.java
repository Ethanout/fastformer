package io.github.fastformer.server.history.commit;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Server-thread state machine for publishing one durable history entry.
 * Once sealed, an index failure retains ownership and retries instead of rolling back.
 */
public final class DurableCommit {
   public enum Phase { PUBLISH_BATCH, SEAL_JOURNAL, PUBLISH_INDEX, DONE, FAILED }

   /** Storage and journal operations supplied by the owning history service. */
   public interface Actions {
      CompletableFuture<Void> publishBatch();
      boolean sealJournal();
      CompletableFuture<Void> publishIndex();
   }

   private final Actions actions;
   private final int retryDelayTicks;
   private Phase phase = Phase.PUBLISH_BATCH;
   private CompletableFuture<Void> pending;
   private int retryTicks;
   private Throwable failure;

   public DurableCommit(Actions actions, int retryDelayTicks) {
      this.actions = Objects.requireNonNull(actions, "actions");
      if (retryDelayTicks < 0) throw new IllegalArgumentException("negative retry delay");
      this.retryDelayTicks = retryDelayTicks;
      this.retryTicks = retryDelayTicks;
      this.pending = attempt(actions::publishBatch);
   }

   /** Advances completed stages without blocking on outstanding IO. Terminal phases are stable. */
   public Phase poll() {
      return switch (phase) {
         case PUBLISH_BATCH -> pollBatch();
         case PUBLISH_INDEX -> pollIndex();
         case SEAL_JOURNAL, DONE, FAILED -> phase;
      };
   }

   public Phase phase() { return phase; }
   public Optional<Throwable> failure() { return Optional.ofNullable(failure); }

   public boolean indexFailed() {
      return phase == Phase.PUBLISH_INDEX && pending.isCompletedExceptionally();
   }

   private Phase pollBatch() {
      if (!pending.isDone()) return phase;
      if (!completedSuccessfully()) return phase = Phase.FAILED;
      phase = Phase.SEAL_JOURNAL;
      try {
         if (!actions.sealJournal()) {
            failure = new IllegalStateException("journal seal was refused");
            return phase = Phase.FAILED;
         }
      } catch (RuntimeException rejected) {
         failure = rejected;
         return phase = Phase.FAILED;
      }
      phase = Phase.PUBLISH_INDEX;
      pending = attempt(actions::publishIndex);
      return phase;
   }

   private Phase pollIndex() {
      if (!pending.isDone()) return phase;
      if (completedSuccessfully()) return phase = Phase.DONE;
      if (retryTicks-- > 0) return phase;
      retryTicks = retryDelayTicks;
      pending = attempt(actions::publishIndex);
      return phase;
   }

   private boolean completedSuccessfully() {
      try {
         pending.join();
         failure = null;
         return true;
      } catch (RuntimeException rejected) {
         failure = rejected.getCause() == null ? rejected : rejected.getCause();
         return false;
      }
   }

   private static CompletableFuture<Void> attempt(Supplier<CompletableFuture<Void>> action) {
      try {
         return Objects.requireNonNull(action.get(), "commit operation returned no completion");
      } catch (RuntimeException rejected) {
         return CompletableFuture.failedFuture(rejected);
      }
   }
}
