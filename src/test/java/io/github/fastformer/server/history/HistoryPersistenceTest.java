package io.github.fastformer.server.history;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class HistoryPersistenceTest {
   @Test
   void anOlderSuccessfulSnapshotCannotClearANewerPublicationFailure() {
      var persistence = new HistoryPersistence();
      var events = new ArrayList<Boolean>();
      var oldSnapshot = new CompletableFuture<Void>();
      var newPublication = new CompletableFuture<Void>();
      persistence.track(true, oldSnapshot, Runnable::run, events::add);
      persistence.track(false, newPublication, Runnable::run, events::add);
      newPublication.completeExceptionally(new IllegalStateException("Disk unavailable"));
      oldSnapshot.complete(null);
      assertTrue(persistence.dirty());
      assertTrue(persistence.idle());
      assertFalse(persistence.settled());
      assertEquals(List.of(true), events);
      for (int tick = 0; tick < 100; tick++) assertFalse(persistence.retryReady());
      assertTrue(persistence.retryReady());
      persistence.track(true, CompletableFuture.completedFuture(null), Runnable::run, events::add);
      assertTrue(persistence.settled());
      assertFalse(persistence.retryReady());
      assertEquals(List.of(true, false), events);
   }

   @Test
   void ordinarySuccessDoesNotClearDirtyStateAndPendingWritesBlockRetries() {
      var persistence = new HistoryPersistence();
      persistence.track(false, CompletableFuture.failedFuture(new IllegalStateException()), Runnable::run, ignored -> {});
      var pending = new CompletableFuture<Void>();
      persistence.track(false, pending, Runnable::run, ignored -> {});
      for (int tick = 0; tick < 150; tick++) assertFalse(persistence.retryReady());
      pending.complete(null);
      assertTrue(persistence.dirty());
      for (int tick = 0; tick < 100; tick++) assertFalse(persistence.retryReady());
      assertTrue(persistence.retryReady());
   }

   @Test
   void closingRejectsNewTrackingAndIgnoresQueuedCompletionCallbacks() {
      var persistence = new HistoryPersistence();
      var callbacks = new ArrayList<Runnable>();
      var events = new ArrayList<Boolean>();
      persistence.track(false, CompletableFuture.failedFuture(new IllegalStateException()), callbacks::add, events::add);
      assertEquals(1, callbacks.size());
      persistence.close();
      callbacks.getFirst().run();
      assertTrue(events.isEmpty());
      assertFalse(persistence.dirty());
      assertFalse(persistence.retryReady());
      assertThrows(IllegalStateException.class, () -> persistence.track(false,
         CompletableFuture.completedFuture(null), Runnable::run, events::add));
   }
}
