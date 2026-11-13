package io.github.fastformer.core.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IoExecutorsTest {
   @Test
   void namedWorkersHaveBoundedAdmissionAndIndependentQueues() throws Exception {
      var failures = new ArrayList<String>();
      var owner = new IoExecutors("test-owner", failures::add);
      var worker = owner.serial("one", 1);
      var release = new CountDownLatch(1);
      var entered = new CountDownLatch(1);
      try {
         worker.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
         });
         assertTrue(entered.await(5, TimeUnit.SECONDS));
         worker.submit(() -> 1);
         assertThrows(RejectedExecutionException.class, () -> worker.submit(() -> 2));
         assertSame(worker, owner.serial("one", 1));
         assertThrows(IllegalArgumentException.class, () -> owner.serial("one", 2));
         assertEquals(3, owner.serial("two", 1).submit(() -> 3).get(5, TimeUnit.SECONDS));
      } finally {
         release.countDown();
         assertTrue(owner.shutdown(Duration.ofSeconds(5)));
      }
      assertTrue(failures.isEmpty());
      assertThrows(IllegalStateException.class, () -> owner.serial("three", 1));
      assertThrows(RejectedExecutionException.class, () -> worker.submit(() -> 4));
   }

   @Test
   void gracefulCloseFinishesAcceptedWork() {
      var owner = new IoExecutors("test-drain", message -> { throw new AssertionError(message); });
      var worker = owner.serial("io", 16);
      var writes = new AtomicInteger();
      for (int index = 0; index < 10; index++) worker.submit(writes::incrementAndGet);
      assertTrue(owner.shutdown(Duration.ofSeconds(5)));
      assertEquals(10, writes.get());
      assertTrue(worker.isTerminated());
      assertTrue(owner.shutdown(Duration.ZERO));
   }

   @Test
   void expiredDeadlineInterruptsRunningWorkAndCancelsQueuedFutures() throws Exception {
      var failures = new ArrayList<String>();
      var owner = new IoExecutors("test-deadline", failures::add);
      var worker = owner.serial("io", 2);
      var entered = new CountDownLatch(1);
      var interrupted = new CountDownLatch(1);
      worker.execute(() -> {
         entered.countDown();
         try { new CountDownLatch(1).await(); }
         catch (InterruptedException stopped) { interrupted.countDown(); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var queued = worker.submit(() -> 7);
      assertFalse(owner.shutdown(Duration.ZERO));
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
      assertTrue(queued.isCancelled());
      assertEquals(1, failures.size());
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
   }

   @Test
   void invalidConfigurationDoesNotCreateWorkers() {
      try (var owner = new IoExecutors("test-invalid", message -> {})) {
         assertThrows(IllegalArgumentException.class, () -> owner.serial("", 1));
         assertThrows(IllegalArgumentException.class, () -> owner.serial("name", 0));
         assertThrows(IllegalArgumentException.class, () -> owner.shutdown(Duration.ofNanos(-1)));
      }
   }
}
