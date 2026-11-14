package io.github.fastformer.server.network.upload;

import com.mojang.logging.LogUtils;
import io.github.fastformer.common.workspace.submission.OperationWorkspacePlan;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;

/** Bounds running decodes and pending server completions; cancellation retires both. */
final class WorkspaceDecodeQueue {
   private volatile Generation generation = new Generation();

   private static final class Generation {
      final Map<UUID, DecodeTask> owners = new ConcurrentHashMap<>();
      final Semaphore slots = new Semaphore(3);
   }

   boolean busy(UUID owner) { return generation.owners.containsKey(owner); }

   boolean contains(UUID owner, UUID transfer) {
      DecodeTask task = generation.owners.get(owner);
      return task != null && task.transfer.equals(transfer);
   }

   synchronized void cancel(UUID owner) {
      DecodeTask task = generation.owners.get(owner);
      if (task != null) task.retire();
   }

   synchronized void clear() {
      Generation previous = generation;
      generation = new Generation();
      previous.owners.values().forEach(DecodeTask::retire);
   }

   synchronized boolean submit(UUID owner, UUID transfer, Callable<OperationWorkspacePlan> work,
      Executor worker, Executor server, BiConsumer<OperationWorkspacePlan, Exception> completion) {
      Generation current = generation;
      if (!current.slots.tryAcquire()) return false;
      var task = new DecodeTask(current, owner, transfer, work, worker, server, completion);
      if (current.owners.putIfAbsent(owner, task) != null) {
         current.slots.release();
         return false;
      }
      try {
         worker.execute(task);
         return true;
      } catch (RejectedExecutionException exception) {
         task.retire();
         return false;
      } catch (RuntimeException | Error failure) {
         task.retire();
         throw failure;
      }
   }

   private record DecodeResult(OperationWorkspacePlan plan, Exception error) {}

   /** Cancelled callbacks may stay in the server queue, but must not retain decoded plans. */
   private static final class DecodeWork implements Callable<Void> {
      private Callable<OperationWorkspacePlan> source;
      private DecodeResult result;
      private boolean retired;

      DecodeWork(Callable<OperationWorkspacePlan> source) { this.source = source; }

      @Override public Void call() {
         Callable<OperationWorkspacePlan> computation = takeSource();
         if (computation == null) return null;
         DecodeResult completed;
         try { completed = new DecodeResult(computation.call(), null); }
         catch (Exception error) { completed = new DecodeResult(null, error); }
         synchronized (this) { if (!retired) result = completed; }
         return null;
      }

      private synchronized Callable<OperationWorkspacePlan> takeSource() {
         Callable<OperationWorkspacePlan> computation = source;
         source = null;
         return retired ? null : computation;
      }

      synchronized DecodeResult takeResult() {
         DecodeResult completed = result;
         result = null;
         return completed;
      }

      synchronized void retire() { retired = true; source = null; result = null; }
   }

   private final class DecodeTask extends FutureTask<Void> {
      private final Generation current;
      private final UUID owner;
      private final UUID transfer;
      private final Executor worker;
      private final Executor server;
      private final BiConsumer<OperationWorkspacePlan, Exception> completion;
      private final DecodeWork work;

      DecodeTask(Generation current, UUID owner, UUID transfer, Callable<OperationWorkspacePlan> work,
         Executor worker, Executor server, BiConsumer<OperationWorkspacePlan, Exception> completion) {
         this(current, owner, transfer, new DecodeWork(work), worker, server, completion);
      }

      private DecodeTask(Generation current, UUID owner, UUID transfer, DecodeWork work,
         Executor worker, Executor server, BiConsumer<OperationWorkspacePlan, Exception> completion) {
         super(work);
         this.current = current;
         this.owner = owner;
         this.transfer = transfer;
         this.worker = worker;
         this.server = server;
         this.completion = completion;
         this.work = work;
      }

      void retire() {
         cancel(true);
         if (worker instanceof ThreadPoolExecutor pool) pool.remove(this);
         release();
      }

      void release() {
         work.retire();
         // Identity, not transfer UUID: a reconnect may already be decoding the same transfer again.
         if (current.owners.remove(owner, this)) current.slots.release();
      }

      boolean current() {
         return current == generation && current.owners.get(owner) == this && !isCancelled();
      }

      @Override protected void done() {
         if (!current()) { release(); return; }
         try {
            get();
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            release();
            return;
         } catch (ExecutionException failed) {
            release();
            LogUtils.getLogger().error("Workspace decode task failed for {}", transfer, failed.getCause());
            return;
         }
         dispatch();
      }

      private void dispatch() {
         try {
            server.execute(() -> {
               try {
                  DecodeResult result = work.takeResult();
                  if (current() && result != null) completion.accept(result.plan(), result.error());
               }
               finally { release(); }
            });
         } catch (RuntimeException | Error failure) {
            release();
            LogUtils.getLogger().error("Unable to publish workspace decode for {}", transfer, failure);
         }
      }
   }
}
