package io.github.fastformer.client.session.storage;

import io.github.fastformer.client.lifecycle.ClientBackgroundTasks;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Serial disk work with bounded admission and client-thread completions. */
public final class ClientStorageQueue {
   private final ThreadPoolExecutor worker;
   private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
   private final Semaphore slots = new Semaphore(3);
   public ClientStorageQueue() {
      this(ClientBackgroundTasks.serial("session-storage", 8));
   }

   public ClientStorageQueue(ThreadPoolExecutor worker) {
      this.worker = Objects.requireNonNull(worker, "worker");
   }
   public <T> boolean submit(Callable<T> task, BiConsumer<T, Exception> completion) {
      return submitPrepared(() -> new Task<>(task, completion));
   }

   public record Task<T>(Callable<T> work, BiConsumer<T, Exception> completion) {
      public Task {
         Objects.requireNonNull(work, "work");
         Objects.requireNonNull(completion, "completion");
      }
   }

   public <T> boolean submitPrepared(Supplier<Task<T>> preparation) {
      if (!slots.tryAcquire()) return false;
      try {
         Task<T> task = Objects.requireNonNull(preparation.get(), "prepared task");
         worker.execute(() -> run(task));
         return true;
      } catch (RejectedExecutionException exception) { slots.release(); return false; }
      catch (RuntimeException | Error failure) { slots.release(); throw failure; }
   }

   private <T> void run(Task<T> task) {
      T value = null;
      Exception failure = null;
      try {
         value = task.work().call();
      } catch (Exception exception) {
         failure = exception;
      } catch (Error error) {
         failure = new ExecutionException(error);
         throw error;
      } finally {
         // Even a failed worker must finish the caller's pending operation. Keep its
         // admission slot until the client consumes the result, just as on success.
         T result = value;
         Exception error = failure;
         results.add(() -> { slots.release(); task.completion().accept(result, error); });
      }
   }
   public void drain() {
      for (int i = results.size(); i > 0; i--) { Runnable result = results.poll(); if (result != null) result.run(); }
   }

   /** Includes completed work whose client-thread callback has not yet run. */
   public boolean idle() {
      return slots.availablePermits() == 3;
   }
}
