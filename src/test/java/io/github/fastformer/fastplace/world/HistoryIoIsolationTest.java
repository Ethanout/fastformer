package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HistoryIoIsolationTest {
   @Test
   void blockedCompletedHistoryDoesNotHoldTheJournalExecutor() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      CompletableFuture<Void> history = CompletableFuture.runAsync(() -> {
         started.countDown();
         try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("history worker was not released");
         } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
         }
      }, WorldHistoryPersistence.executor());
      try {
         assertTrue(started.await(5, TimeUnit.SECONDS));
         CompletableFuture<Void> journal = CompletableFuture.runAsync(() -> {}, PersistentRecoveryJournal.executor());
         journal.get(5, TimeUnit.SECONDS);
      } finally {
         release.countDown();
         history.get(5, TimeUnit.SECONDS);
      }
   }
}
