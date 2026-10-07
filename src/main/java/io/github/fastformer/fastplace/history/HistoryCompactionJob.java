package io.github.fastformer.fastplace.history;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** One bounded worker queue. Workers receive snapshots and never access mutable owner state. */
final class HistoryCompactionJob {
   private static final Executor WORKER = new java.util.concurrent.ThreadPoolExecutor(
      1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32),
      runnable -> {
         Thread thread = new Thread(runnable, "FastFormer-history-compaction");
         thread.setDaemon(true);
         return thread;
      }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

   private final long revision;
   private final TinkerHistoryMergeLimits limits;
   private final HistoryCompactionPolicy policy;
   private final List<UUID> undoOrder;
   private final List<UUID> redoOrder;
   final CompletableFuture<Optional<TinkerHistoryCompactor.Replacement>> result;

   private HistoryCompactionJob(HistoryMemoryCache memory, HistoryOrderCatalog order, TinkerHistoryMergeLimits limits, HistoryCompactionPolicy policy) {
      this.revision = memory.revision();
      this.limits = limits;
      this.policy = policy;
      this.undoOrder = order.order(true);
      this.redoOrder = order.order(false);
      List<WorldChangeBatch> batches = memory.snapshot(true);
      this.result = CompletableFuture.supplyAsync(() -> TinkerHistoryCompactor.compact(batches, limits, policy), WORKER);
   }

   static Optional<HistoryCompactionJob> start(HistoryMemoryCache memory, HistoryOrderCatalog order, int limit) {
      return start(memory, order, TinkerHistoryMergeLimits.uniform(limit));
   }

   static Optional<HistoryCompactionJob> start(HistoryMemoryCache memory, HistoryOrderCatalog order, TinkerHistoryMergeLimits limits) {
      return start(memory, order, limits, HistoryStorageConfig.compactionPolicy());
   }

   static Optional<HistoryCompactionJob> start(HistoryMemoryCache memory, HistoryOrderCatalog order, TinkerHistoryMergeLimits limits, HistoryCompactionPolicy policy) {
      var loaded = memory.operationIds(true);
      var durable = order.order(true);
      if (!policy.enabled() || (limits.near() < 2 && limits.middle() < 2 && limits.far() < 2)
         || loaded.size() < policy.protectedRecords() + 2 || !order.order(false).isEmpty()
         || durable.size() < loaded.size() || !durable.subList(0, loaded.size()).equals(loaded)) return Optional.empty();
      try {
         return Optional.of(new HistoryCompactionJob(memory, order, limits, policy));
      } catch (RejectedExecutionException ignored) {
         return Optional.empty();
      }
   }

   boolean matches(HistoryMemoryCache memory, HistoryOrderCatalog order, int currentLimit) {
      return matches(memory, order, TinkerHistoryMergeLimits.uniform(currentLimit));
   }

   boolean matches(HistoryMemoryCache memory, HistoryOrderCatalog order, TinkerHistoryMergeLimits currentLimits) {
      return matches(memory, order, currentLimits, HistoryStorageConfig.compactionPolicy());
   }

   boolean matches(HistoryMemoryCache memory, HistoryOrderCatalog order, TinkerHistoryMergeLimits currentLimits, HistoryCompactionPolicy currentPolicy) {
      return revision == memory.revision() && limits.equals(currentLimits) && policy.equals(currentPolicy)
         && undoOrder.equals(order.order(true)) && redoOrder.equals(order.order(false));
   }
}
