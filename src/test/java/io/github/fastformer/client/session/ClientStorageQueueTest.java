package io.github.fastformer.client.session;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientStorageQueueTest {
   @Test
   void rejectedWorkDoesNotInvalidateAnAcceptedFileWrite() throws Exception {
      var queue = new ClientStorageQueue();
      var release = new CountDownLatch(1);
      var completed = new AtomicInteger();
      var prepared = new AtomicInteger();
      var file = java.nio.file.Path.of("queue-test-" + java.util.UUID.randomUUID());
      Object ticket = io.github.fastformer.client.operation.clipboard.OperationClipboardStore.reserve(file);
      try {
         assertTrue(queue.submit(() -> {
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return io.github.fastformer.client.operation.clipboard.OperationClipboardStore.current(file, ticket);
         }, (current, error) -> {
            assertNull(error);
            assertTrue(current);
            completed.incrementAndGet();
         }));
         for (int i = 0; i < 2; i++) {
            assertTrue(queue.submit(() -> true, (value, error) -> completed.incrementAndGet()));
         }
         assertFalse(queue.submitPrepared(() -> {
            prepared.incrementAndGet();
            io.github.fastformer.client.operation.clipboard.OperationClipboardStore.reserve(file);
            return new ClientStorageQueue.Task<>(() -> true, (value, error) -> fail("Rejected work completed"));
         }));
         assertEquals(0, prepared.get());
         release.countDown();
         long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
         while (completed.get() != 3 && System.nanoTime() < deadline) { queue.drain(); Thread.yield(); }
         assertEquals(3, completed.get());
      } finally { release.countDown(); }
   }

   @Test
   void preparationFailureReturnsItsAdmissionSlot() {
      var queue = new ClientStorageQueue();
      for (int i = 0; i < 4; i++) {
         assertThrows(IllegalArgumentException.class, () -> queue.submitPrepared(() -> {
            throw new IllegalArgumentException("Invalid snapshot");
         }));
      }
      assertTrue(queue.submit(() -> true, (value, error) -> {}));
   }

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
