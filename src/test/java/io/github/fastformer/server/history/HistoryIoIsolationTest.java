package io.github.fastformer.server.history;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.core.async.IoExecutors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HistoryIoIsolationTest {
   @Test
   void blockedCompletedHistoryDoesNotHoldTheJournalExecutor() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      try (var workers = new IoExecutors("history-isolation-test", ignored -> {})) {
      CompletableFuture<Void> history = CompletableFuture.runAsync(() -> {
         started.countDown();
         try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("history worker was not released");
         } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
         }
      }, workers.serial("history-io", 4));
      try {
         assertTrue(started.await(5, TimeUnit.SECONDS));
         CompletableFuture<Void> journal = CompletableFuture.runAsync(() -> {}, workers.serial("recovery", 4));
         journal.get(5, TimeUnit.SECONDS);
      } finally {
         release.countDown();
         history.get(5, TimeUnit.SECONDS);
      }
      }
   }
}
