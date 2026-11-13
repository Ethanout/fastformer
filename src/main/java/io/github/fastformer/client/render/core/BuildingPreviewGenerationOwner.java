package io.github.fastformer.client.render.core;

import io.github.fastformer.client.render.model.BuildingBlockResult;
import io.github.fastformer.client.render.model.BuildingPreviewKey;
import io.github.fastformer.core.async.LatestOnlyTask;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/** Owns the one asynchronous building-preview result that may update the current cache. */
final class BuildingPreviewGenerationOwner {
   private final LatestOnlyTask<BuildingPreviewKey, BuildingBlockResult> generation = new LatestOnlyTask<>();

   void start(BuildingPreviewKey key, Callable<BuildingBlockResult> work, Executor executor) {
      generation.start(key, work, executor);
   }

   boolean completed() {
      return generation.completed();
   }

   Optional<BuildingBlockResult> takeCompleted() throws InterruptedException, ExecutionException {
      return generation.takeCompleted()
         .filter(completion -> completion.value() != null && completion.value().key().equals(completion.key()))
         .map(LatestOnlyTask.Completion::value);
   }

   void cancel() {
      generation.cancel();
   }
}
