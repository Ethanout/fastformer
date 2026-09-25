package io.github.fastformer.network.transfer;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceDecodeQueueTest {
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
