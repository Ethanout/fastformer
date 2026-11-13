package io.github.fastformer.server.network.upload;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceDecodeQueueTest {
   @Test
   void cancellingOneOwnerDiscardsPendingCompletionWithoutReleasingItsReplacement() {
      var queue = new WorkspaceDecodeQueue();
      var callbacks = new java.util.ArrayDeque<Runnable>();
      UUID owner = UUID.randomUUID(), transfer = UUID.randomUUID(), other = UUID.randomUUID();
      var completed = new AtomicInteger();
      assertTrue(queue.submit(owner, transfer, () -> null, Runnable::run, callbacks::addLast,
         (value, error) -> fail("cancelled completion ran")));
      assertTrue(queue.submit(other, UUID.randomUUID(), () -> null, Runnable::run, callbacks::addLast,
         (value, error) -> completed.incrementAndGet()));
      queue.cancel(owner);
      assertFalse(queue.busy(owner));
      assertTrue(queue.busy(other));
      assertTrue(queue.submit(owner, transfer, () -> null, Runnable::run, callbacks::addLast,
         (value, error) -> completed.incrementAndGet()));
      callbacks.removeFirst().run();
      assertTrue(queue.contains(owner, transfer), "retired completion released the replacement request");
      callbacks.removeFirst().run();
      assertFalse(queue.busy(other));
      callbacks.removeFirst().run();
      assertFalse(queue.busy(owner));
      assertEquals(2, completed.get());
   }

   @Test
   void cancellationBeforeDecodeStartsDoesNotExecuteOldWork() {
      var queue = new WorkspaceDecodeQueue();
      var work = new java.util.ArrayDeque<Runnable>();
      UUID owner = UUID.randomUUID();
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> {
         fail("cancelled decode ran"); return null;
      }, work::addLast, Runnable::run, (value, error) -> fail("cancelled callback ran")));
      queue.cancel(owner);
      queue.cancel(owner);
      work.removeFirst().run();
      assertFalse(queue.busy(owner));
      for (int i = 0; i < 3; i++) {
         assertTrue(queue.submit(UUID.randomUUID(), UUID.randomUUID(), () -> null,
            Runnable::run, callback -> {}, (value, error) -> {}));
      }
      assertFalse(queue.submit(owner, UUID.randomUUID(), () -> null,
         Runnable::run, Runnable::run, (value, error) -> fail("capacity exceeded")));
      queue.clear();
   }

   @Test
   void cancellationDuringDecodeCannotPublishIntoAReplacementOfTheSameTransfer() throws Exception {
      var queue = new WorkspaceDecodeQueue();
      var started = new CountDownLatch(1);
      var finishOld = new CountDownLatch(1);
      var callbacks = new LinkedBlockingQueue<Runnable>();
      UUID owner = UUID.randomUUID(), transfer = UUID.randomUUID();
      try {
         assertTrue(queue.submit(owner, transfer, () -> {
            started.countDown();
            // Deliberately ignore cancellation: a decoder may be in uninterruptible library code.
            boolean interrupted = false;
            while (true) {
               try { finishOld.await(); break; }
               catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return null;
         }, worker, callbacks::add, (value, error) -> fail("cancelled decode published")));
         assertTrue(started.await(5, TimeUnit.SECONDS));
         queue.cancel(owner);
         var completed = new AtomicInteger();
         assertTrue(queue.submit(owner, transfer, () -> null, Runnable::run, callbacks::add,
            (value, error) -> completed.incrementAndGet()));
         finishOld.countDown();
         workers.serial("decode", 2).submit(() -> {}).get(5, TimeUnit.SECONDS);
         assertTrue(queue.contains(owner, transfer));
         assertEquals(1, callbacks.size());
         callbacks.remove().run();
         assertEquals(1, completed.get());
         assertFalse(queue.busy(owner));
      } finally {
         finishOld.countDown();
         queue.clear();
      }
   }

   @Test
   void completionFailureReleasesTheOwnerAndAllowsAnotherRequest() {
      var queue = new WorkspaceDecodeQueue();
      var callbacks = new java.util.ArrayDeque<Runnable>();
      UUID owner = UUID.randomUUID();
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> null, Runnable::run, callbacks::addLast,
         (value, error) -> { throw new IllegalStateException("completion fault"); }));
      assertThrows(IllegalStateException.class, () -> callbacks.removeFirst().run());
      assertFalse(queue.busy(owner));
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> null, Runnable::run, Runnable::run, (value, error) -> {}));
   }

   @Test
   void unexpectedWorkerFaultRetiresItsOwnerBeforePropagating() {
      var queue = new WorkspaceDecodeQueue();
      UUID owner = UUID.randomUUID();
      assertThrows(IllegalStateException.class, () -> queue.submit(owner, UUID.randomUUID(), () -> null,
         task -> { throw new IllegalStateException("broken executor"); }, Runnable::run, (value, error) -> fail()));
      assertFalse(queue.busy(owner));
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> null, Runnable::run, Runnable::run, (value, error) -> {}));
   }

   @Test
   void separateQueuesDoNotCancelOrConsumeEachOthersCapacity() {
      var first = new WorkspaceDecodeQueue();
      var second = new WorkspaceDecodeQueue();
      var callbacks = new java.util.ArrayDeque<Runnable>();
      UUID owner = UUID.randomUUID(), transfer = UUID.randomUUID();
      assertTrue(first.submit(owner, transfer, () -> null, Runnable::run, callbacks::addLast, (v, e) -> fail()));
      var completed = new AtomicInteger();
      assertTrue(second.submit(owner, transfer, () -> null, Runnable::run, callbacks::addLast, (v, e) -> completed.incrementAndGet()));
      first.clear();
      assertTrue(second.contains(owner, transfer));
      callbacks.removeFirst().run();
      callbacks.removeFirst().run();
      assertEquals(1, completed.get());
   }

   @Test
   void decodeExceptionIsDeliveredAndReleasesOwnerAfterConsumption() {
      var queue = new WorkspaceDecodeQueue();
      var callbacks = new java.util.ArrayDeque<Runnable>();
      UUID owner = UUID.randomUUID();
      var failure = new java.io.IOException("invalid plan");
      var observed = new java.util.concurrent.atomic.AtomicReference<Exception>();
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> { throw failure; }, Runnable::run,
         callbacks::addLast, (value, error) -> { assertNull(value); observed.set(error); }));
      assertTrue(queue.busy(owner));
      callbacks.removeFirst().run();
      assertSame(failure, observed.get());
      assertFalse(queue.busy(owner));
   }

   private final io.github.fastformer.core.async.IoExecutors workers =
      new io.github.fastformer.core.async.IoExecutors("decode-test", message -> fail(message));
   private final Executor worker = workers.serial("decode", 2);

   @org.junit.jupiter.api.AfterEach
   void closeWorkers() { workers.close(); }
   @Test
   void serverResetDropsOldCallbacksWithoutReleasingNewTransfers() throws Exception {
      var queue = new WorkspaceDecodeQueue();
      var callbacks = new LinkedBlockingQueue<Runnable>();
      UUID owner = UUID.randomUUID(), transfer = UUID.randomUUID();
      try {
         assertTrue(queue.submit(owner, transfer, () -> null, worker, callbacks::add,
            (value, error) -> fail("retired server callback ran")));
         Runnable retired = callbacks.poll(5, TimeUnit.SECONDS);
         assertNotNull(retired);
         queue.clear();
         assertFalse(queue.busy(owner));
         var completed = new AtomicInteger();
         assertTrue(queue.submit(owner, transfer, () -> null, worker, callbacks::add,
            (value, error) -> completed.incrementAndGet()));
         Runnable current = callbacks.poll(5, TimeUnit.SECONDS);
         assertNotNull(current);
         retired.run();
         assertTrue(queue.contains(owner, transfer), "old completion released new request");
         current.run();
         assertEquals(1, completed.get());
         assertFalse(queue.busy(owner));
      } finally {
         queue.clear();
      }
   }

   @Test
   void fatalDecodeFailureReleasesOwnerAndAllCapacity() throws Exception {
      assertFatalFailureReleasesCapacity(false);
   }

   @Test
   void fatalServerDispatchFailureReleasesOwnerAndAllCapacity() throws Exception {
      assertFatalFailureReleasesCapacity(true);
   }

   private void assertFatalFailureReleasesCapacity(boolean failDispatch) throws Exception {
      var queue = new WorkspaceDecodeQueue();
      UUID owner = UUID.randomUUID();
      var fatal = new AssertionError("simulated fatal failure");
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> {
         if (!failDispatch) throw fatal;
         return null;
      }, worker, callback -> { throw fatal; }, (value, error) -> fail("unexpected completion")));
      // FutureTask owns fatal completion; an uncaught handler is no longer the completion channel.
      workers.serial("decode", 2).submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertFalse(queue.busy(owner));

      var callbacks = new LinkedBlockingQueue<Runnable>();
      for (int i = 0; i < 3; i++) {
         assertTrue(queue.submit(i == 0 ? owner : UUID.randomUUID(), UUID.randomUUID(),
            () -> null, worker, callbacks::add, (value, error) -> assertNull(error)));
         assertNotNull(callbacks.poll(5, TimeUnit.SECONDS));
      }
   }

   @Test
   void clearCancelsQueuedDecodesBeforeTheyCanTouchTheOldWorld() {
      var queued = new java.util.ArrayDeque<Runnable>();
      var queue = new WorkspaceDecodeQueue();
      UUID owner = UUID.randomUUID();
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> {
         fail("retired decode ran"); return null;
      }, queued::addLast, Runnable::run, (value, error) -> fail("retired callback ran")));
      queue.clear();
      assertFalse(queue.busy(owner));
      queued.removeFirst().run();
      var completed = new AtomicInteger();
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> null, Runnable::run,
         Runnable::run, (value, error) -> completed.incrementAndGet()));
      assertEquals(1, completed.get());
   }

   @Test
   void rejectedWorkerDoesNotRetainTheOwnerOrCapacity() {
      var queue = new WorkspaceDecodeQueue();
      UUID owner = UUID.randomUUID();
      assertFalse(queue.submit(owner, UUID.randomUUID(), () -> null,
         task -> { throw new RejectedExecutionException("closed"); }, Runnable::run, (value, error) -> fail()));
      assertFalse(queue.busy(owner));
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> null, Runnable::run, Runnable::run, (value, error) -> {}));
   }

   @Test
   void waitingServerCompletionsRemainBoundedAndOwnersCannotDecodeTwice() throws Exception {
      var queue = new WorkspaceDecodeQueue();
      var callbacks = new LinkedBlockingQueue<Runnable>();
      var completed = new AtomicInteger();
      UUID owner = UUID.randomUUID(), transfer = UUID.randomUUID();
      assertTrue(queue.submit(owner, transfer, () -> null, worker, callbacks::add, (v, e) -> completed.incrementAndGet()));
      assertFalse(queue.submit(owner, UUID.randomUUID(), () -> null, worker, callbacks::add, (v, e) -> fail()));
      for (int i = 0; i < 2; i++) assertTrue(queue.submit(UUID.randomUUID(), UUID.randomUUID(), () -> null, worker, callbacks::add, (v, e) -> completed.incrementAndGet()));
      Runnable first = callbacks.poll(5, TimeUnit.SECONDS);
      assertNotNull(first);
      assertFalse(queue.submit(UUID.randomUUID(), UUID.randomUUID(), () -> null, worker, callbacks::add, (v, e) -> fail()));
      assertTrue(queue.contains(owner, transfer));
      first.run();
      for (int i = 0; i < 2; i++) { Runnable callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback); callback.run(); }
      assertFalse(queue.busy(owner));
      assertEquals(3, completed.get());
   }
}
