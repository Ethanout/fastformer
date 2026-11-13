package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.HistoryEntry;

import io.github.fastformer.server.recovery.spi.HistoryReconciler;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** History-side startup reconciliation bound to one running server. */
public final class HistoryRecoveryReconciler implements HistoryReconciler {
   private final HistoryRepository<HistoryEntry> repository;

   public HistoryRecoveryReconciler(HistoryService history) {
      this.repository = Objects.requireNonNull(history, "history").repository();
   }

   @Override
   public CompletableFuture<Void> reconcile(UUID owner, UUID operation) {
      return repository.reconcileCommittedJournal(owner, operation);
   }
}
