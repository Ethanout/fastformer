package io.github.fastformer.network.transfer;

import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Bounds both retained plans and decode concurrency. Completions run on the server thread. */
public final class WorkspaceDecodeQueue {
   private volatile Generation generation = new Generation();

   private static final class Generation {
      final java.util.Map<UUID, UUID> owners = new ConcurrentHashMap<>();
      final Semaphore slots = new Semaphore(3);
      final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(2), runnable -> {
         Thread thread = new Thread(runnable, "fastformer-workspace-decode");
         thread.setDaemon(true);
         return thread;
      });
   }

   public boolean busy(UUID owner) { return generation.owners.containsKey(owner); }
   public boolean contains(UUID owner, UUID transfer) { return transfer.equals(generation.owners.get(owner)); }

   /** Retires callbacks and capacity retained by a server that no longer ticks. */
   public synchronized void clear() {
      Generation previous = generation;
      generation = new Generation();
      previous.executor.shutdownNow();
      previous.owners.clear();
   }

   public boolean submit(UUID owner, UUID transfer, Callable<OperationWorkspacePlan> work, Executor server,
      BiConsumer<OperationWorkspacePlan, Exception> completion) {
      Generation current = generation;
      if (!current.slots.tryAcquire()) return false;
      if (current.owners.putIfAbsent(owner, transfer) != null) { current.slots.release(); return false; }
      try {
         current.executor.execute(() -> {
            try {
               OperationWorkspacePlan plan = null;
               Exception failure = null;
               try { plan = work.call(); } catch (Exception exception) { failure = exception; }
               OperationWorkspacePlan result = plan;
               Exception error = failure;
               try {
                  server.execute(() -> {
                     try {
                        if (current == generation) completion.accept(result, error);
                     } finally { release(current, owner, transfer); }
                  });
               } catch (RuntimeException exception) { release(current, owner, transfer); }
            } catch (Error failure) {
               release(current, owner, transfer);
               throw failure;
            }
         });
         return true;
      } catch (RejectedExecutionException exception) {
         release(current, owner, transfer);
         return false;
      }
   }

   private void release(Generation current, UUID owner, UUID transfer) {
      if (current.owners.remove(owner, transfer)) current.slots.release();
   }
}
