package io.github.fastformer.fastplace.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Owns one asynchronous history-page load and the request that must resume
 * after that page becomes available. WorldHistoryManager owns task execution.
 */
final class HistoryTaskScheduler {
   private PendingPageLoad pendingPageLoad;
   private HistoryPageLoadPlan failedPagePlan;

   void startPageLoad(
      HistoryPageLoadPlan plan, CompletableFuture<List<WorldChangeBatch>> future
   ) {
      if (this.pendingPageLoad != null) {
         throw new IllegalStateException("A history page load is already pending");
      }
      this.failedPagePlan = null;
      this.pendingPageLoad = new PendingPageLoad(
         Objects.requireNonNull(plan, "plan"),
         Objects.requireNonNull(future, "future")
      );
   }

   Optional<CompletedPageLoad> takeCompletedPageLoad() {
      PendingPageLoad pending = this.pendingPageLoad;
      if (pending == null || !pending.future().isDone()) {
         return Optional.empty();
      }
      this.pendingPageLoad = null;
      try {
         List<WorldChangeBatch> batches = pending.future().join();
         this.failedPagePlan = null;
         return Optional.of(new CompletedPageLoad(pending.plan(), batches));
      } catch (RuntimeException failure) {
         this.failedPagePlan = pending.plan();
         throw failure;
      }
   }

   boolean hasPendingPageLoad() {
      return this.pendingPageLoad != null;
   }

   boolean cancelPageLoad() {
      PendingPageLoad pending = this.pendingPageLoad;
      this.pendingPageLoad = null;
      this.failedPagePlan = null;
      if (pending == null) {
         return false;
      }
      pending.future().cancel(false);
      return true;
   }

   /**
    * The last page request that failed after the future settled.
    *
    * <p>The plan stays until a later request starts another load. Memory history
    * and the durable order stay with the manager. This scheduler only remembers
    * which missing page the player asked for.</p>
    */
   Optional<HistoryPageLoadPlan> failedPagePlan() {
      return Optional.ofNullable(this.failedPagePlan);
   }

   void clearFailedPagePlan() {
      this.failedPagePlan = null;
   }

   void pageMergeFailed(HistoryPageLoadPlan plan) {
      this.failedPagePlan = Objects.requireNonNull(plan, "plan");
   }

   private record PendingPageLoad(
      HistoryPageLoadPlan plan, CompletableFuture<List<WorldChangeBatch>> future
   ) {
   }

   record CompletedPageLoad(HistoryPageLoadPlan plan, List<WorldChangeBatch> batches) {
      CompletedPageLoad {
         batches = List.copyOf(batches);
      }
   }
}
