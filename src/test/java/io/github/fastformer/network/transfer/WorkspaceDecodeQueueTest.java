package io.github.fastformer.network.transfer;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceDecodeQueueTest {
   @Test
   void serverResetDropsOldCallbacksWithoutReleasingNewTransfers() throws Exception {
      var queue = new WorkspaceDecodeQueue();
      var callbacks = new LinkedBlockingQueue<Runnable>();
      UUID owner = UUID.randomUUID(), transfer = UUID.randomUUID();
      try {
         assertTrue(queue.submit(owner, transfer, () -> null, callbacks::add,
            (value, error) -> fail("retired server callback ran")));
         Runnable retired = callbacks.poll(5, TimeUnit.SECONDS);
         assertNotNull(retired);
         queue.clear();
         assertFalse(queue.busy(owner));
         var completed = new AtomicInteger();
         assertTrue(queue.submit(owner, transfer, () -> null, callbacks::add,
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
      var failures = new LinkedBlockingQueue<Throwable>();
      var fatal = new AssertionError("simulated fatal failure");
      assertTrue(queue.submit(owner, UUID.randomUUID(), () -> {
         Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> failures.add(error));
         if (!failDispatch) throw fatal;
         return null;
      }, callback -> { throw fatal; }, (value, error) -> fail("unexpected completion")));
      assertSame(fatal, failures.poll(5, TimeUnit.SECONDS));
      assertFalse(queue.busy(owner));

      var callbacks = new LinkedBlockingQueue<Runnable>();
      for (int i = 0; i < 3; i++) {
         assertTrue(queue.submit(i == 0 ? owner : UUID.randomUUID(), UUID.randomUUID(),
            () -> null, callbacks::add, (value, error) -> assertNull(error)));
         assertNotNull(callbacks.poll(5, TimeUnit.SECONDS));
      }
   }

   @Test
   void waitingServerCompletionsRemainBoundedAndOwnersCannotDecodeTwice() throws Exception {
      var queue = new WorkspaceDecodeQueue();
      var callbacks = new LinkedBlockingQueue<Runnable>();
      var completed = new AtomicInteger();
      UUID owner = UUID.randomUUID(), transfer = UUID.randomUUID();
      assertTrue(queue.submit(owner, transfer, () -> null, callbacks::add, (v, e) -> completed.incrementAndGet()));
      assertFalse(queue.submit(owner, UUID.randomUUID(), () -> null, callbacks::add, (v, e) -> fail()));
      for (int i = 0; i < 2; i++) assertTrue(queue.submit(UUID.randomUUID(), UUID.randomUUID(), () -> null, callbacks::add, (v, e) -> completed.incrementAndGet()));
      Runnable first = callbacks.poll(5, TimeUnit.SECONDS);
      assertNotNull(first);
      assertFalse(queue.submit(UUID.randomUUID(), UUID.randomUUID(), () -> null, callbacks::add, (v, e) -> fail()));
      assertTrue(queue.contains(owner, transfer));
      first.run();
      for (int i = 0; i < 2; i++) { Runnable callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback); callback.run(); }
      assertFalse(queue.busy(owner));
      assertEquals(3, completed.get());
   }
}
