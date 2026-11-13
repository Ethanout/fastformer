package io.github.fastformer.server.recovery.spi;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Restores a committed operation's history index before startup may delete its journal.
 * Implementations bind their server and storage when the recovery runner is assembled.
 */
@FunctionalInterface
public interface HistoryReconciler {
   CompletableFuture<Void> reconcile(UUID owner, UUID operation);
}
