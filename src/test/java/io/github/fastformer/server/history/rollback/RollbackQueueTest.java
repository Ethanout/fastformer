package io.github.fastformer.server.history.rollback;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class RollbackQueueTest {
   @Test
   void pendingCaptureIsAcceptedEvenWhenProgressFeedbackFails() {
      var queue = new RollbackQueue();
      var ready = new CompletableFuture<Void>();
      var request = request(ready);
      assertTrue(queue.accept(request, false, ignored -> fail("Capture is not ready"),
         (context, key) -> { throw new IllegalStateException("Feedback unavailable"); }));
      assertEquals(1, queue.size());
      assertFalse(queue.isEmpty());
      queue.poll(request.context(), ignored -> fail("Capture is not ready"));
      assertEquals(1, queue.size());
   }

   @Test
   void pollingDoesNotPassAnUnreadyOldestCapture() {
      var queue = new RollbackQueue();
      var pending = request(new CompletableFuture<>());
      queue.enqueue(pending);
      queue.enqueue(request(CompletableFuture.completedFuture(null)));
      var installed = new AtomicInteger();
      queue.poll(pending.context(), ignored -> installed.incrementAndGet());
      assertEquals(0, installed.get());
      assertEquals(2, queue.size());
   }

   @Test
   void emptyReadyCaptureDoesNotCreateAReplayOrRetainAQueueEntry() {
      var queue = new RollbackQueue();
      assertTrue(queue.accept(request(CompletableFuture.completedFuture(null)), false,
         ignored -> fail("Nothing changed"), (context, key) -> fail("Nothing needs retry")));
      assertTrue(queue.isEmpty());
   }

   private static RollbackRequest request(CompletableFuture<Void> ready) {
      return new RollbackRequest(new WorldTaskContext(null, UUID.randomUUID()), Level.OVERWORLD,
         new WorldRecoverySnapshot(new ArrayDeque<>(), Map.of(), ready), null);
   }
}
