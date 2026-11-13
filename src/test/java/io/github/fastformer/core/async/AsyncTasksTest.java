package io.github.fastformer.core.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AsyncTasksTest {
   @Test
   void synchronousResultAndFailureKeepTheirMeaning() {
      assertEquals(7, AsyncTasks.submit(Runnable::run, () -> 7).join());
      var failure = new IllegalStateException("operation failed");
      var future = AsyncTasks.submit(Runnable::run, () -> { throw failure; });
      assertSame(failure, assertThrows(CompletionException.class, future::join).getCause());
   }

   @Test
   void rejectedWorkFailsAdmissionWithoutRunning() {
      var failure = new RejectedExecutionException("closed");
      assertSame(failure, assertThrows(RejectedExecutionException.class,
         () -> AsyncTasks.run(task -> { throw failure; }, () -> { throw new AssertionError("must not run"); })));
   }

   @Test
   void shutdownResolvesDiscardedFuturesAndTheirDependentChains() throws Exception {
      var workers = new IoExecutors("async-stop-test", ignored -> {});
      var executor = workers.serial("io", 2);
      var entered = new CountDownLatch(1);
      executor.execute(() -> {
         entered.countDown();
         try { new CountDownLatch(1).await(); }
         catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var pending = AsyncTasks.submit(executor, () -> 7);
      var terminal = pending.handle((value, failure) -> failure != null);
      assertFalse(workers.shutdown(Duration.ZERO));
      assertTrue(pending.isCancelled());
      assertTrue(terminal.get(5, TimeUnit.SECONDS));
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
   }
}
