package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.HistoryEntry;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Explicit storage boundary for tests that only exercise in-memory task ownership. */
public final class NoDiskHistoryRepository implements HistoryRepository<HistoryEntry> {
   private boolean closed;

   @Override public CompletableFuture<Void> publishNewBatch(UUID owner, HistoryEntry batch, List<UUID> undo, List<UUID> redo) {
      return accepted(null);
   }
   @Override public CompletableFuture<Void> publishIndex(UUID owner, List<UUID> undo, List<UUID> redo) {
      return accepted(null);
   }
   @Override public CompletableFuture<Void> publishSnapshot(UUID owner, List<HistoryEntry> undo, List<HistoryEntry> redo,
      List<UUID> undoOrder, List<UUID> redoOrder) {
      return accepted(null);
   }
   @Override public CompletableFuture<Void> publishBatchOnly(UUID owner, HistoryEntry batch) {
      return accepted(null);
   }
   @Override public CompletableFuture<Void> reconcileCommittedJournal(UUID owner, UUID operation) {
      return accepted(null);
   }
   @Override public CompletableFuture<Snapshot<HistoryEntry>> loadSnapshot(UUID owner, int maxEntriesPerStack, long maxRetainedBytes) {
      return accepted(Snapshot.empty());
   }
   @Override public CompletableFuture<List<HistoryEntry>> loadPage(UUID owner, List<UUID> ids, int maxEntries, long maxRetainedBytes) {
      return accepted(List.of());
   }
   @Override public CompletableFuture<Void> pendingWrites() {
      return CompletableFuture.completedFuture(null);
   }
   @Override public void resumeCleanup(Set<UUID> activeOwners) { }
   @Override public void close() { closed = true; }

   private <T> CompletableFuture<T> accepted(T value) {
      if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Test repository is closed"));
      return CompletableFuture.completedFuture(value);
   }
}
