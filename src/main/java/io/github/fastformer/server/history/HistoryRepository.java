package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.HistoryEntry;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Durable history boundary owned by one history service. */
public interface HistoryRepository<E extends HistoryEntry> extends AutoCloseable {
   CompletableFuture<Void> publishNewBatch(UUID owner, E batch, List<UUID> undo, List<UUID> redo);
   CompletableFuture<Void> publishIndex(UUID owner, List<UUID> undo, List<UUID> redo);
   CompletableFuture<Void> publishSnapshot(UUID owner, List<E> undo, List<E> redo,
      List<UUID> undoOrder, List<UUID> redoOrder);
   CompletableFuture<Void> publishBatchOnly(UUID owner, E batch);
   CompletableFuture<Void> reconcileCommittedJournal(UUID owner, UUID operation);
   CompletableFuture<Snapshot<E>> loadSnapshot(UUID owner, int maxEntriesPerStack, long maxRetainedBytes);
   CompletableFuture<List<E>> loadPage(UUID owner, List<UUID> ids, int maxEntries, long maxRetainedBytes);
   CompletableFuture<Void> pendingWrites();
   void resumeCleanup(Set<UUID> activeOwners);
   @Override void close();

   default CompletableFuture<Void> publishSnapshot(UUID owner, List<E> undo, List<E> redo) {
      return publishSnapshot(owner, undo, redo,
         undo.stream().map(HistoryEntry::operationId).toList(),
         redo.stream().map(HistoryEntry::operationId).toList());
   }

   record Snapshot<E extends HistoryEntry>(List<E> undo, List<E> redo, List<UUID> undoOrder, List<UUID> redoOrder) {
      public Snapshot {
         undo = List.copyOf(undo);
         redo = List.copyOf(redo);
         undoOrder = List.copyOf(undoOrder);
         redoOrder = List.copyOf(redoOrder);
      }

      public static <E extends HistoryEntry> Snapshot<E> empty() {
         return new Snapshot<>(List.of(), List.of(), List.of(), List.of());
      }
   }
}
