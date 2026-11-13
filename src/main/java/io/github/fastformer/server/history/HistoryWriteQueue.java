package io.github.fastformer.server.history;

import io.github.fastformer.core.async.AsyncTasks;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Per-repository admission and owner ordering. Caller cancellation never releases an accepted write. */
final class HistoryWriteQueue implements AutoCloseable {
   private final Map<UUID, CompletableFuture<Void>> owners = new HashMap<>();
   private final Executor executor;
   private final int operationLimit;
   private final long byteLimit;
   private int pendingOperations;
   private long pendingBytes;
   private boolean closed;

   HistoryWriteQueue(Executor executor, int operationLimit, long byteLimit) {
      this.executor = Objects.requireNonNull(executor, "executor");
      if (operationLimit < 1 || byteLimit < 0) throw new IllegalArgumentException("Invalid history queue budget");
      this.operationLimit = operationLimit;
      this.byteLimit = byteLimit;
   }

   <T> CompletableFuture<T> submit(UUID owner, long retainedBytes, Supplier<CompletableFuture<T>> operation) {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(operation, "operation");
      CompletableFuture<T> result = new CompletableFuture<>();
      CompletableFuture<Void> completion = result.handle((ignored, failure) -> null);
      CompletableFuture<Void> previous;
      synchronized (this) {
         if (closed) return failed("History repository is closed");
         if (retainedBytes < 0 || pendingOperations >= operationLimit || retainedBytes > byteLimit - pendingBytes) {
            return failed("History persistence queue is full");
         }
         previous = owners.put(owner, completion);
         pendingOperations++;
         pendingBytes += retainedBytes;
      }
      result.whenComplete((ignored, failure) -> release(owner, completion, retainedBytes));
      if (previous == null) previous = CompletableFuture.completedFuture(null);
      try {
         previous.thenCompose(ignored -> AsyncTasks.submit(executor, operation::get).thenCompose(next -> next))
            .whenComplete((value, failure) -> {
               if (failure == null) result.complete(value);
               else result.completeExceptionally(failure);
            });
      } catch (RuntimeException | Error failure) {
         result.completeExceptionally(failure);
      }
      return result.copy();
   }

   synchronized CompletableFuture<Void> pendingWrites() {
      return CompletableFuture.allOf(owners.values().toArray(CompletableFuture[]::new));
   }

   private synchronized void release(UUID owner, CompletableFuture<Void> completion, long retainedBytes) {
      pendingOperations--;
      pendingBytes -= retainedBytes;
      owners.remove(owner, completion);
   }

   @Override public synchronized void close() {
      closed = true;
   }

   private static <T> CompletableFuture<T> failed(String message) {
      return CompletableFuture.failedFuture(new IOException(message));
   }
}
