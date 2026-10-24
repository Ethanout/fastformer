package io.github.fastformer.fastplace.history;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Server-thread scheduler; detects history changes and retries a saturated worker queue. */
final class HistoryCompactionScheduler {
   private static final int RETRY_TICKS = 20;
   private final Executor executor;
   private HistoryMemoryCache observedMemory;
   private long observedRevision = Long.MIN_VALUE;
   private TinkerHistoryMergeLimits observedLimits;
   private HistoryCompactionPolicy observedPolicy;
   private HistoryCompactionJob running;
   private boolean requested;
   private int retryTicks;

   HistoryCompactionScheduler() { this(HistoryCompactionJob.WORKER); }

   HistoryCompactionScheduler(Executor executor) { this.executor = executor; }

   void request() { requested = true; }

   void clear() {
      running = null;
      requested = false;
      retryTicks = 0;
      observedRevision = Long.MIN_VALUE;
      observedMemory = null;
   }

   boolean running() { return running != null; }

   boolean needsTick(HistoryMemoryCache memory, TinkerHistoryMergeLimits limits, HistoryCompactionPolicy policy) {
      return requested || running != null || changed(memory, limits, policy);
   }

   Optional<TinkerHistoryCompactor.Replacement> poll(HistoryMemoryCache memory, HistoryOrderCatalog order,
      TinkerHistoryMergeLimits limits, HistoryCompactionPolicy policy, boolean ready) {
      if (changed(memory, limits, policy)) {
         observedMemory = memory;
         observedRevision = memory.revision();
         observedLimits = limits;
         observedPolicy = policy;
         requested = true;
      }
      if (running != null) {
         if (!running.result.isDone()) return Optional.empty();
         HistoryCompactionJob completed = running;
         running = null;
         if (!ready || !completed.matches(memory, order, limits, policy)) {
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
         running = HistoryCompactionJob.start(memory, order, limits, policy, executor).orElse(null);
      } catch (RejectedExecutionException saturated) {
         requested = true;
         retryTicks = RETRY_TICKS;
      }
      return Optional.empty();
   }

   private boolean changed(HistoryMemoryCache memory, TinkerHistoryMergeLimits limits, HistoryCompactionPolicy policy) {
      return observedMemory != memory || observedRevision != memory.revision()
         || !limits.equals(observedLimits) || !policy.equals(observedPolicy);
   }
}
