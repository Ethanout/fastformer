package io.github.fastformer.server.history.memory;

import io.github.fastformer.server.history.entry.HistoryEntry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Owns one asynchronous history-page load and the request that must resume
 * after that page becomes available. HistoryService owns task execution.
 */
public final class HistoryTaskScheduler<E extends HistoryEntry> {
   private PendingPageLoad<E> pendingPageLoad;
   private HistoryPageLoadPlan failedPagePlan;

   public void startPageLoad(
      HistoryPageLoadPlan plan, CompletableFuture<List<E>> future
   ) {
      if (this.pendingPageLoad != null) {
         throw new IllegalStateException("A history page load is already pending");
      }
      this.failedPagePlan = null;
      this.pendingPageLoad = new PendingPageLoad<>(
         Objects.requireNonNull(plan, "plan"),
         Objects.requireNonNull(future, "future")
      );
   }

   public Optional<CompletedPageLoad<E>> takeCompletedPageLoad() {
      PendingPageLoad<E> pending = this.pendingPageLoad;
      if (pending == null || !pending.future().isDone()) {
         return Optional.empty();
      }
      this.pendingPageLoad = null;
      try {
         List<E> batches = pending.future().join();
         this.failedPagePlan = null;
         return Optional.of(new CompletedPageLoad<>(pending.plan(), batches));
      } catch (RuntimeException failure) {
         this.failedPagePlan = pending.plan();
         throw failure;
      }
   }

   public boolean hasPendingPageLoad() {
      return this.pendingPageLoad != null;
   }

   public boolean cancelPageLoad() {
      PendingPageLoad<E> pending = this.pendingPageLoad;
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
   public Optional<HistoryPageLoadPlan> failedPagePlan() {
      return Optional.ofNullable(this.failedPagePlan);
   }

   public void clearFailedPagePlan() {
      this.failedPagePlan = null;
   }

   public void pageMergeFailed(HistoryPageLoadPlan plan) {
      this.failedPagePlan = Objects.requireNonNull(plan, "plan");
   }

   private record PendingPageLoad<E extends HistoryEntry>(
      HistoryPageLoadPlan plan, CompletableFuture<List<E>> future
   ) {
   }

   public record CompletedPageLoad<E extends HistoryEntry>(HistoryPageLoadPlan plan, List<E> batches) {
      public CompletedPageLoad {
         batches = List.copyOf(batches);
      }
   }
}
