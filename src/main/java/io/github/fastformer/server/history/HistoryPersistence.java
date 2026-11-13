package io.github.fastformer.server.history;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Owns publication generations, dirty state, and retry timing for one history owner. */
final class HistoryPersistence implements AutoCloseable {
   private static final int RETRY_TICKS = 100;
   private int pending;
   private boolean dirty;
   private long generation;
   private int retryTicks;
   private boolean closed;

   boolean idle() { return pending == 0; }
   boolean dirty() { return dirty; }
   boolean settled() { return idle() && !dirty; }

   void track(boolean clearsDirty, CompletableFuture<Void> publication, Executor completionThread, Consumer<Boolean> changed) {
      Objects.requireNonNull(publication, "publication");
      Objects.requireNonNull(completionThread, "completion thread");
      Objects.requireNonNull(changed, "state notification");
      if (closed) throw new IllegalStateException("History persistence is closed");
      long acceptedGeneration = ++generation;
      pending++;
      publication.whenComplete((ignored, failure) -> completionThread.execute(() -> {
         if (closed) return;
         pending--;
         boolean previouslyDirty = dirty;
         if (failure != null) {
            dirty = true;
            retryTicks = RETRY_TICKS;
         } else if (clearsDirty && pending == 0 && acceptedGeneration == generation) {
            dirty = false;
         }
         if (previouslyDirty != dirty) changed.accept(dirty);
      }));
   }

   boolean retryReady() {
      if (closed || !dirty || pending > 0) return false;
      if (retryTicks > 0) {
         retryTicks--;
         return false;
      }
      return true;
   }

   @Override
   public void close() {
      closed = true;
   }
}
