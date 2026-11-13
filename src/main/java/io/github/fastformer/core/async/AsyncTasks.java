package io.github.fastformer.core.async;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/** Completion-bearing queue tasks: cancelling a queued task also resolves its result. */
public final class AsyncTasks {
   private AsyncTasks() {}

   public static <T> CompletableFuture<T> submit(Executor executor, Callable<T> operation) {
      Objects.requireNonNull(executor, "executor");
      Objects.requireNonNull(operation, "operation");
      var result = new CompletableFuture<T>();
      var task = new FutureTask<T>(operation) {
         @Override protected void done() {
            if (isCancelled()) { result.cancel(false); return; }
            try {
               result.complete(get());
            } catch (InterruptedException interrupted) {
               Thread.currentThread().interrupt();
               result.completeExceptionally(interrupted);
            } catch (ExecutionException failed) {
               result.completeExceptionally(failed.getCause());
            }
         }
      };
      executor.execute(task);
      return result;
   }

   public static CompletableFuture<Void> run(Executor executor, Runnable operation) {
      return submit(executor, () -> { operation.run(); return null; });
   }
}
