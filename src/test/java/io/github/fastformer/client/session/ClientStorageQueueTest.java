package io.github.fastformer.client.session;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientStorageQueueTest {
   @Test
   void slowDiskDoesNotBlockCallerAndUndrainedResultsKeepAdmissionBounded() throws Exception {
      var queue = new ClientStorageQueue();
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var finished = new CountDownLatch(3);
      var published = new AtomicInteger();
      Thread caller = Thread.currentThread();
      try {
         assertTrue(queue.submit(() -> { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); finished.countDown(); return 1; },
            (value, error) -> { assertSame(caller, Thread.currentThread()); assertNull(error); published.addAndGet(value); }));
         assertTrue(started.await(5, TimeUnit.SECONDS));
         for (int i = 0; i < 2; i++) assertTrue(queue.submit(() -> { finished.countDown(); return 1; }, (v, e) -> published.addAndGet(v)));
         assertFalse(queue.submit(() -> 0, (v, e) -> fail("queue exceeded its capacity")));
         release.countDown();
         assertTrue(finished.await(5, TimeUnit.SECONDS));
         assertEquals(0, published.get());
         assertFalse(queue.submit(() -> 0, (v, e) -> {}));
         long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
         while (published.get() != 3 && System.nanoTime() < deadline) { queue.drain(); Thread.yield(); }
         assertEquals(3, published.get());
      } finally { release.countDown(); }
   }

   @Test
   void diskFailureReturnsToCallerWithoutPublishingSuccess() throws Exception {
      var queue = new ClientStorageQueue();
      var failure = new AtomicReference<Exception>();
      assertTrue(queue.submit(() -> { throw new java.io.IOException("disk failure"); }, (value, error) -> { assertNull(value); failure.set(error); }));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (failure.get() == null && System.nanoTime() < deadline) { queue.drain(); Thread.yield(); }
      assertInstanceOf(java.io.IOException.class, failure.get());
   }
}
