package io.github.fastformer.client.render.core;

import io.github.fastformer.client.render.model.BuildingBlockResult;
import io.github.fastformer.client.render.model.BuildingPreviewKey;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Owns the one asynchronous building-preview result that may update the current cache. */
final class BuildingPreviewGenerationOwner {
   private BuildingPreviewKey key;
   private Future<BuildingBlockResult> future;

   void replace(BuildingPreviewKey key, Future<BuildingBlockResult> future) {
      cancel();
      this.key = Objects.requireNonNull(key, "key");
      this.future = Objects.requireNonNull(future, "future");
   }

   boolean completed() {
      return this.future != null && this.future.isDone();
   }

   Optional<BuildingBlockResult> takeCompleted() throws InterruptedException, ExecutionException {
      if (!completed()) {
         return Optional.empty();
      }
      Future<BuildingBlockResult> completedFuture = this.future;
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
      if (this.future != null) {
         this.future.cancel(true);
      }
      this.future = null;
      this.key = null;
   }
}
