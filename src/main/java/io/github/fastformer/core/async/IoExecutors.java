package io.github.fastformer.core.async;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Owns named bounded workers for one application or server lifetime; installs no JVM hooks. */
public final class IoExecutors implements AutoCloseable {
   private final String owner;
   private final Consumer<String> reportFailure;
   private final Map<String, Worker> workers = new LinkedHashMap<>();
   private boolean closed;

   public IoExecutors(String owner, Consumer<String> reportFailure) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.reportFailure = Objects.requireNonNull(reportFailure, "reportFailure");
   }

   /** Returns the same worker for the same name; a conflicting capacity is a configuration error. */
   public synchronized ThreadPoolExecutor serial(String name, int queueCapacity) {
      if (closed) throw new IllegalStateException("Executor owner is closed: " + owner);
      if (name == null || name.isBlank() || queueCapacity < 1) {
         throw new IllegalArgumentException("A worker name and positive queue capacity are required");
      }
      Worker existing = workers.get(name);
      if (existing != null) {
         if (existing.capacity != queueCapacity) throw new IllegalArgumentException("Conflicting capacity for " + name);
         return existing.executor;
      }
      var sequence = new AtomicInteger();
      var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
         new ArrayBlockingQueue<>(queueCapacity), task -> {
            var thread = new Thread(task, owner + "-" + name + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
         }, new ThreadPoolExecutor.AbortPolicy());
      workers.put(name, new Worker(queueCapacity, executor));
      return executor;
   }

   /**
    * Stops admission first, then waits within one shared deadline. At the deadline,
    * interrupts workers and cancels queued futures so callers cannot wait forever.
    * Running work must honor interruption; a timeout is reported to the owner.
    */
   public boolean shutdown(Duration timeout) {
      Objects.requireNonNull(timeout, "timeout");
      if (timeout.isNegative()) throw new IllegalArgumentException("Shutdown timeout must not be negative");
      long budget = timeout.toNanos();
      long started = System.nanoTime();
      List<Worker> closing;
      synchronized (this) {
         closed = true;
         closing = List.copyOf(workers.values());
         closing.forEach(worker -> worker.executor.shutdown());
      }
      boolean complete = true;
      boolean interrupted = false;
      for (Worker worker : closing) {
         long remaining = Math.max(0L, budget - (System.nanoTime() - started));
         try {
            if (worker.executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) continue;
         } catch (InterruptedException failure) {
            interrupted = true;
         }
         complete = false;
         cancelQueued(worker.executor.shutdownNow());
      }
      if (interrupted) Thread.currentThread().interrupt();
      if (!complete) reportFailure.accept("Background workers exceeded shutdown deadline for " + owner);
      return complete;
   }

   @Override public void close() { shutdown(Duration.ofSeconds(5)); }

   private static void cancelQueued(List<Runnable> tasks) {
      for (Runnable task : tasks) if (task instanceof Future<?> future) future.cancel(false);
   }

   private record Worker(int capacity, ThreadPoolExecutor executor) {}
}
