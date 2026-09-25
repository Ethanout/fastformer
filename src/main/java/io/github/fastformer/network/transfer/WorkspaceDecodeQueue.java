package io.github.fastformer.network.transfer;

import io.github.fastformer.fastplace.OperationWorkspacePlan;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Bounds both retained plans and decode concurrency. Completions run on the server thread. */
public final class WorkspaceDecodeQueue {
   private final java.util.Map<UUID, UUID> owners = new ConcurrentHashMap<>();
   private final Semaphore slots = new Semaphore(3);
   private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(2), runnable -> {
         Thread thread = new Thread(runnable, "fastformer-workspace-decode");
         thread.setDaemon(true);
         return thread;
      });

   public boolean busy(UUID owner) { return owners.containsKey(owner); }
   public boolean contains(UUID owner, UUID transfer) { return transfer.equals(owners.get(owner)); }

   public boolean submit(UUID owner, UUID transfer, Callable<OperationWorkspacePlan> work, Executor server,
      BiConsumer<OperationWorkspacePlan, Exception> completion) {
      if (!slots.tryAcquire()) return false;
      if (owners.putIfAbsent(owner, transfer) != null) { slots.release(); return false; }
      try {
         executor.execute(() -> {
            OperationWorkspacePlan plan = null;
            Exception failure = null;
            try { plan = work.call(); } catch (Exception exception) { failure = exception; }
            OperationWorkspacePlan result = plan;
            Exception error = failure;
            try {
               server.execute(() -> {
                  try { completion.accept(result, error); } finally { release(owner, transfer); }
               });
            } catch (RuntimeException exception) { release(owner, transfer); }
         });
         return true;
      } catch (RejectedExecutionException exception) {
         release(owner, transfer);
         return false;
      }
   }

   private void release(UUID owner, UUID transfer) {
      if (owners.remove(owner, transfer)) slots.release();
   }
}
