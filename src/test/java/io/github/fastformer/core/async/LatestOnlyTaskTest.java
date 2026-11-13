package io.github.fastformer.core.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LatestOnlyTaskTest {
   @Test
   void resultWaitsForOwnerConsumptionAndIsDeliveredOnce() throws Exception {
      var task = new LatestOnlyTask<String, Integer>();
      var published = new ArrayList<Integer>();
      task.start("key", () -> 7, Runnable::run);
      assertTrue(task.pending());
      assertTrue(task.completed());
      assertTrue(published.isEmpty());
      task.drain(result -> published.add(result.value()), failure -> { throw new AssertionError(failure); });
      task.drain(result -> published.add(result.value()), failure -> { throw new AssertionError(failure); });
      assertEquals(List.of(7), published);
      assertFalse(task.pending());
   }

   @Test
   void rejectedExecutionUsesTheSameDeferredFailureBoundary() {
      var task = new LatestOnlyTask<String, Integer>();
      var failure = new RejectedExecutionException("no room");
      task.start("key", () -> 7, work -> { throw failure; });
      assertTrue(task.completed());
      var actual = assertThrows(ExecutionException.class, task::takeCompleted);
      assertSame(failure, actual.getCause());
      assertFalse(task.pending());
      assertFalse(task.completed());
   }

   @Test
   void sameKeyReplacementInvalidatesLateCompletion() throws Exception {
      var task = new LatestOnlyTask<String, Integer>();
      var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var exited = new CountDownLatch(1);
      try {
         task.start("same", () -> {
            started.countDown();
            try {
               while (release.getCount() != 0) {
                  try { release.await(); } catch (InterruptedException ignored) { }
               }
               return 1;
            } finally { exited.countDown(); }
         }, executor);
         assertTrue(started.await(5, TimeUnit.SECONDS));
         var queue = new ArrayDeque<Runnable>();
         task.start("same", () -> 2, queue::add);
         release.countDown();
         assertTrue(exited.await(5, TimeUnit.SECONDS));
         assertFalse(task.completed());
         queue.remove().run();
         assertEquals(2, task.takeCompleted().orElseThrow().value());
      } finally {
         release.countDown();
         task.cancel();
         executor.shutdownNow();
         assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }
   }

   @Test
   void cancelRemovesOnlyItsOwnQueuedTask() throws Exception {
      var task = new LatestOnlyTask<String, Integer>();
      var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      executor.execute(() -> {
         started.countDown();
         try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      try {
         assertTrue(started.await(5, TimeUnit.SECONDS));
         Runnable other = () -> {};
         executor.execute(other);
         for (int index = 0; index < 100; index++) task.start("key", () -> 1, executor);
         assertEquals(2, executor.getQueue().size());
         task.cancel();
         assertEquals(List.of(other), List.copyOf(executor.getQueue()));
         assertFalse(task.pending());
         assertFalse(task.completed());
      } finally {
         release.countDown();
         executor.shutdownNow();
         assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }
   }

   @Test
   void failurePolicyCanClearRetryOrPropagateOnTheOwnerThread() {
      var task = new LatestOnlyTask<String, Integer>();
      var failure = new IllegalStateException("failed");
      task.start("key", () -> { throw failure; }, Runnable::run);
      var failures = new ArrayList<Throwable>();
      task.drain(result -> {}, failures::add);
      assertEquals(List.of(failure), failures);
      task.start("key", () -> { throw failure; }, Runnable::run);
      assertSame(failure, assertThrows(IllegalStateException.class,
         () -> task.drain(result -> {}, error -> { throw (IllegalStateException) error; })));
      assertFalse(task.pending());
   }
}
