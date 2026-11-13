package io.github.fastformer.server.history.memory;

import io.github.fastformer.server.history.entry.HistoryReplacement;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.compaction.HistoryCompactionStrategy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import io.github.fastformer.core.async.AsyncTasks;

/** One bounded worker queue. Workers receive snapshots and never access mutable owner state. */
final class HistoryCompactionJob<E extends HistoryEntry> {
   private final long revision;
   private final HistoryMemoryCache<E> memory;
   private final HistoryCompactionStrategy<E> strategy;
   private final List<UUID> undoOrder;
   private final List<UUID> redoOrder;
   final CompletableFuture<Optional<HistoryReplacement<E>>> result;

   private HistoryCompactionJob(HistoryMemoryCache<E> memory, HistoryOrderCatalog order, HistoryCompactionStrategy<E> strategy, Executor executor) {
      this.revision = memory.revision();
      this.memory = memory;
      this.strategy = strategy;
      this.undoOrder = order.order(true);
      this.redoOrder = order.order(false);
      List<E> batches = memory.snapshot(true);
      this.result = AsyncTasks.submit(executor, () -> strategy.compact(batches));
   }

   static <E extends HistoryEntry> Optional<HistoryCompactionJob<E>> start(HistoryMemoryCache<E> memory, HistoryOrderCatalog order,
      HistoryCompactionStrategy<E> strategy, Executor executor) {
      var loaded = memory.operationIds(true);
      var durable = order.order(true);
      if (!strategy.enabled()
         || loaded.size() < strategy.protectedRecords() + 2 || !order.order(false).isEmpty()
         || durable.size() < loaded.size() || !durable.subList(0, loaded.size()).equals(loaded)) return Optional.empty();
      return Optional.of(new HistoryCompactionJob<>(memory, order, strategy, executor));
   }

   boolean matches(HistoryMemoryCache<E> memory, HistoryOrderCatalog order, HistoryCompactionStrategy<E> currentStrategy) {
      return this.memory == memory && revision == memory.revision() && strategy.equals(currentStrategy)
         && undoOrder.equals(order.order(true)) && redoOrder.equals(order.order(false));
   }
}
