package io.github.fastformer.client.render.core;

import io.github.fastformer.client.render.model.BuildingBlockResult;
import io.github.fastformer.client.render.model.BuildingPreviewKey;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import io.github.fastformer.client.session.ClientTickMailbox;

/** Owns the one asynchronous building-preview result that may update the current cache. */
final class BuildingPreviewGenerationOwner {
   private BuildingPreviewKey key;
   private Future<BuildingBlockResult> future;
   private Executor executor;
   private final ClientTickMailbox<Future<BuildingBlockResult>> results = new ClientTickMailbox<>(ignored -> { });

   void start(BuildingPreviewKey key, Callable<BuildingBlockResult> work, Executor executor) {
      cancel();
      this.key = Objects.requireNonNull(key, "key");
      long epoch = results.epoch();
      var task = new FutureTask<BuildingBlockResult>(work) {
         @Override
         protected void done() {
            results.post(epoch, this);
         }
      };
      this.future = task;
      this.executor = executor;
      try {
         executor.execute(task);
      } catch (RuntimeException exception) {
         cancel();
         throw exception;
      }
   }

   boolean completed() {
      return results.hasUnfinishedEvents();
   }

   Optional<BuildingBlockResult> takeCompleted() throws InterruptedException, ExecutionException {
      if (!completed()) {
         return Optional.empty();
      }
      var completed = new java.util.ArrayList<Future<BuildingBlockResult>>(1);
      results.drain(event -> {
         if (event == this.future) completed.add(event);
      });
      if (completed.isEmpty()) return Optional.empty();
      Future<BuildingBlockResult> completedFuture = completed.getFirst();
      BuildingPreviewKey completedKey = this.key;
      try {
         BuildingBlockResult result = completedFuture.get();
         return result != null && result.key().equals(completedKey)
            ? Optional.of(result)
            : Optional.empty();
      } finally {
         if (this.future == completedFuture) {
            this.future = null;
            this.key = null;
         }
      }
   }

   void cancel() {
      results.invalidate();
      if (this.future != null) {
         this.future.cancel(true);
         if (executor instanceof java.util.concurrent.ThreadPoolExecutor pool && future instanceof Runnable task) {
            pool.remove(task);
         }
      }
      this.future = null;
      this.executor = null;
      this.key = null;
   }
}
