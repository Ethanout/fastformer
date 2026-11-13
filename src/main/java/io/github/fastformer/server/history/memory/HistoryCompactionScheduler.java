package io.github.fastformer.server.history.memory;

import io.github.fastformer.server.history.compaction.HistoryCompactionStrategy;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.entry.HistoryReplacement;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Server-thread scheduler; detects history changes and retries a saturated worker queue. */
public final class HistoryCompactionScheduler<E extends HistoryEntry> {
   private static final int RETRY_TICKS = 20;
   private final Executor executor;
   private HistoryMemoryCache<E> observedMemory;
   private long observedRevision = Long.MIN_VALUE;
   private HistoryCompactionStrategy<E> observedStrategy;
   private HistoryCompactionJob<E> running;
   private boolean requested;
   private int retryTicks;

   public HistoryCompactionScheduler() { this.executor = null; }

   public HistoryCompactionScheduler(Executor executor) { this.executor = executor; }

   public void request() { requested = true; }

   public void clear() {
      if (running != null) running.result.cancel(false);
      running = null;
      requested = false;
      retryTicks = 0;
      observedRevision = Long.MIN_VALUE;
      observedMemory = null;
      observedStrategy = null;
   }

   public boolean running() { return running != null; }

   public boolean needsTick(HistoryMemoryCache<E> memory, HistoryCompactionStrategy<E> strategy) {
      return requested || running != null || changed(memory, strategy);
   }

   public Optional<HistoryReplacement<E>> poll(HistoryMemoryCache<E> memory, HistoryOrderCatalog order,
      HistoryCompactionStrategy<E> strategy, boolean ready) {
      return poll(memory, order, strategy, ready, java.util.Objects.requireNonNull(executor));
   }

   public Optional<HistoryReplacement<E>> poll(HistoryMemoryCache<E> memory, HistoryOrderCatalog order,
      HistoryCompactionStrategy<E> strategy, boolean ready, Executor executor) {
      if (changed(memory, strategy)) {
         observedMemory = memory;
         observedRevision = memory.revision();
         observedStrategy = strategy;
         requested = true;
      }
      if (running != null) {
         if (!running.result.isDone()) return Optional.empty();
         HistoryCompactionJob<E> completed = running;
         running = null;
         if (!ready || !completed.matches(memory, order, strategy)) {
            requested = true;
            return Optional.empty();
         }
         return completed.result.join();
      }
      if (!ready || !requested) return Optional.empty();
      if (retryTicks > 0) {
         retryTicks--;
         return Optional.empty();
      }
      requested = false;
      try {
         running = HistoryCompactionJob.start(memory, order, strategy, executor).orElse(null);
      } catch (RejectedExecutionException saturated) {
         requested = true;
         retryTicks = RETRY_TICKS;
      }
      return Optional.empty();
   }

   private boolean changed(HistoryMemoryCache<E> memory, HistoryCompactionStrategy<E> strategy) {
      return observedMemory != memory || observedRevision != memory.revision()
         || !strategy.equals(observedStrategy);
   }
}
