package io.github.fastformer.client.session;

import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Serial disk work with bounded admission and client-thread completions. */
public final class ClientStorageQueue {
   private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8), task -> { var thread = new Thread(task, "fastformer-client-storage"); thread.setDaemon(true); return thread; });
   private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> results = new java.util.concurrent.ConcurrentLinkedQueue<>();
   private final Semaphore slots = new Semaphore(3);
   public ClientStorageQueue() {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
         worker.shutdown();
         try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
               org.slf4j.LoggerFactory.getLogger(ClientStorageQueue.class).warn("Client storage did not finish within the shutdown deadline");
            }
         } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
      }, "fastformer-client-storage-shutdown"));
   }
   public <T> boolean submit(Callable<T> task, BiConsumer<T, Exception> completion) {
      return submitPrepared(() -> new Task<>(task, completion));
   }

   public record Task<T>(Callable<T> work, BiConsumer<T, Exception> completion) {}

   public <T> boolean submitPrepared(Supplier<Task<T>> preparation) {
      if (!slots.tryAcquire()) return false;
      try {
         Task<T> task = preparation.get();
         worker.execute(() -> {
            T value = null;
            Exception failure = null;
            try { value = task.work().call(); } catch (Exception exception) { failure = exception; }
            T result = value;
            Exception error = failure;
            results.add(() -> { slots.release(); task.completion().accept(result, error); });
         });
         return true;
      } catch (RejectedExecutionException exception) { slots.release(); return false; }
      catch (RuntimeException | Error failure) { slots.release(); throw failure; }
   }
   public void drain() {
      for (int i = results.size(); i > 0; i--) { Runnable result = results.poll(); if (result != null) result.run(); }
   }
}
