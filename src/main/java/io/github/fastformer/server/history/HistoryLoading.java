package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.memory.HistoryPageLoadPlan;
import io.github.fastformer.server.history.memory.HistoryTaskScheduler;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Owns initial snapshot loading, older-page loading, and their distinct failure policies. */
final class HistoryLoading<E extends HistoryEntry> implements AutoCloseable {
   private static final long PAGE_BYTES = 64L * 1024L * 1024L;
   private final HistoryTaskScheduler<E> pages = new HistoryTaskScheduler<>();
   private CompletableFuture<HistoryRepository.Snapshot<E>> snapshot;
   private boolean snapshotFailed;
   private boolean closed;

   boolean snapshotPending() { return snapshot != null; }
   boolean snapshotFailed() { return snapshotFailed; }
   boolean pending() { return snapshotPending() || pages.hasPendingPageLoad(); }
   HistoryTaskScheduler<E> pages() { return pages; }

   CompletableFuture<Void> snapshotCompletion() {
      return snapshot == null ? CompletableFuture.completedFuture(null) : snapshot.thenApply(ignored -> null);
   }

   void startSnapshot(CompletableFuture<HistoryRepository.Snapshot<E>> future) {
      if (closed) throw new IllegalStateException("History loading is closed");
      if (snapshot != null) throw new IllegalStateException("A history snapshot load is already pending");
      snapshot = Objects.requireNonNull(future, "snapshot future");
   }

   void attachSnapshot(Consumer<HistoryRepository.Snapshot<E>> merge) {
      if (snapshot == null || !snapshot.isDone()) return;
      CompletableFuture<HistoryRepository.Snapshot<E>> ready = snapshot;
      snapshot = null;
      try {
         merge.accept(ready.join());
         snapshotFailed = false;
      } catch (RuntimeException failure) {
         snapshotFailed = true;
         throw failure;
      }
   }

   void startPage(UUID owner, HistoryPageLoadPlan plan, HistoryRepository<E> repository) {
      if (closed) throw new IllegalStateException("History loading is closed");
      pages.startPageLoad(plan, repository.loadPage(owner, plan.operationIds(), plan.operationIds().size(), PAGE_BYTES));
   }

   Optional<HistoryPageLoadPlan> attachPage(Consumer<HistoryTaskScheduler.CompletedPageLoad<E>> merge) {
      Optional<HistoryTaskScheduler.CompletedPageLoad<E>> completed = pages.takeCompletedPageLoad();
      if (completed.isEmpty()) return Optional.empty();
      HistoryTaskScheduler.CompletedPageLoad<E> page = completed.orElseThrow();
      try {
         merge.accept(page);
      } catch (RuntimeException failure) {
         pages.pageMergeFailed(page.plan());
         throw failure;
      }
      return Optional.of(page.plan());
   }

   @Override
   public void close() {
      if (closed) return;
      closed = true;
      if (snapshot != null) {
         snapshot.cancel(false);
         snapshot = null;
      }
      pages.cancelPageLoad();
   }
}
