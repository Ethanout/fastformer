package io.github.fastformer.server.task;

import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.task.WorldWriteTask;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldTaskQueueTest {
   @Test void failureBeforeExtractionKeepsTheWriterUntilRecoveryCanBeTransferred() {
      var queue = new WorldTaskQueue<Writer>();
      var context = new WorldTaskContext(null, UUID.randomUUID());
      var task = new Writer();
      task.failExtraction = true;
      queue.put(context.owner(), task);
      WorldTaskQueue.RecoveryAcceptance<Writer> acceptance = (ignored, writer, snapshot) -> {
         assertSame(writer.snapshot, snapshot);
         return TaskCancellationResult.CANCELLED_BEFORE_WRITE;
      };
      assertEquals(TaskCancellationResult.RECOVERY_BLOCKED, queue.transfer(context, task, acceptance));
      assertSame(task, queue.get(context.owner()));
      assertTrue(queue.handingOver(context.owner()));
      task.failExtraction = false;
      assertEquals(TaskCancellationResult.CANCELLED_BEFORE_WRITE, queue.transfer(context, task, acceptance));
      assertEquals(TaskCancellationResult.CANCELLED_BEFORE_WRITE, queue.transfer(context, task, acceptance));
      assertEquals(2, task.extractions);
   }

   @Test void rejectedAndFailedAcceptanceRetainTheSameSnapshot() {
      var attempts = new AtomicInteger();
      var queue = new WorldTaskQueue<Writer>();
      WorldTaskQueue.RecoveryAcceptance<Writer> acceptance = (context, task, snapshot) -> {
         assertSame(task.snapshot, snapshot);
         return switch (attempts.incrementAndGet()) {
            case 1 -> throw new IllegalStateException("injected acceptance failure");
            case 2 -> TaskCancellationResult.RECOVERY_BLOCKED;
            default -> TaskCancellationResult.ROLLBACK_STARTED;
         };
      };
      var context = new WorldTaskContext(null, UUID.randomUUID());
      var task = new Writer();
      queue.put(context.owner(), task);
      assertEquals(TaskCancellationResult.RECOVERY_BLOCKED, queue.transfer(context, task, acceptance));
      assertTrue(queue.handingOver(context.owner()));
      assertEquals(TaskCancellationResult.RECOVERY_BLOCKED, queue.transfer(context, task, acceptance));
      assertEquals(TaskCancellationResult.ROLLBACK_STARTED, queue.transfer(context, task, acceptance));
      assertEquals(TaskCancellationResult.ROLLBACK_STARTED, queue.transfer(context, task, acceptance));
      assertEquals(1, task.extractions);
      assertEquals(3, attempts.get());
      assertTrue(queue.remove(context.owner(), task));
      assertFalse(queue.handingOver(context.owner()));
   }

   @Test void staleCallbacksCannotRemoveOrTransferSuccessor() {
      var accepted = new AtomicInteger();
      var queue = new WorldTaskQueue<Writer>();
      WorldTaskQueue.RecoveryAcceptance<Writer> acceptance = (context, task, snapshot) -> {
         accepted.incrementAndGet();
         return TaskCancellationResult.CANCELLED_BEFORE_WRITE;
      };
      var context = new WorldTaskContext(null, UUID.randomUUID());
      var old = new Writer();
      var current = new Writer();
      queue.put(context.owner(), old);
      assertThrows(IllegalStateException.class, () -> queue.put(context.owner(), current));
      assertTrue(queue.remove(context.owner(), old));
      queue.put(context.owner(), current);
      assertFalse(queue.remove(context.owner(), old));
      assertEquals(TaskCancellationResult.NOT_ACTIVE, queue.transfer(context, old, acceptance));
      assertSame(current, queue.get(context.owner()));
      assertEquals(0, current.extractions);
      assertEquals(0, accepted.get());
   }

   @Test void traversalIsASnapshotAndSeparateQueuesDoNotShareOwners() {
      var first = new WorldTaskQueue<Writer>();
      var second = new WorldTaskQueue<Writer>();
      UUID owner = UUID.randomUUID();
      var task = new Writer();
      first.put(owner, task);
      first.put(owner, task);
      var owners = first.owners();
      var tasks = first.tasks();
      first.clear();
      assertEquals(java.util.List.of(owner), owners);
      assertEquals(java.util.List.of(task), tasks);
      assertFalse(second.containsKey(owner));
   }

   @Test void closingEndsAdmissionBeforeCallbacksAndCleansEveryTaskDespiteFailures() {
      var queue = new WorldTaskQueue<Writer>();
      UUID first = UUID.randomUUID(), second = UUID.randomUUID();
      queue.put(first, new Writer());
      queue.put(second, new Writer());
      var cleaned = new AtomicInteger();
      var failure = assertThrows(IllegalStateException.class, () -> queue.close(task -> {
         assertFalse(queue.containsKey(first));
         assertFalse(queue.containsKey(second));
         assertThrows(IllegalStateException.class, () -> queue.put(first, new Writer()));
         cleaned.incrementAndGet();
         throw new IllegalStateException("injected cleanup failure");
      }));
      assertEquals(2, cleaned.get());
      assertEquals(1, failure.getSuppressed().length);
      queue.close(task -> fail("repeated close ran cleanup twice"));
      assertFalse(queue.containsKey(first));
   }

   private static final class Writer implements WorldWriteTask {
      private final WorldRecoverySnapshot snapshot = new WorldRecoverySnapshot(
         new ArrayDeque<>(), Map.of(), CompletableFuture.completedFuture(null));
      private int extractions;
      private boolean failExtraction;
      @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
      @Override public PersistentRecoveryJournal journal() { return null; }
      @Override public void releaseAfterCancelledJournal(WorldTaskContext context) {}
      @Override public WorldRecoverySnapshot stopAndTransferRecovery() {
         extractions++;
         if (failExtraction) throw new IllegalStateException("injected stop failure before extraction");
         return snapshot;
      }
   }
}
