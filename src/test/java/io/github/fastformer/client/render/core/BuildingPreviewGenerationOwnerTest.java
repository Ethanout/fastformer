package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.render.model.BuildingBlockResult;
import io.github.fastformer.client.render.model.BuildingPreviewKey;
import java.util.List;
import java.util.Set;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BuildingPreviewGenerationOwnerTest {
   @Test
   void replacementRemovesOnlyThisOwnersQueuedWork() throws Exception {
      var executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
         new java.util.concurrent.LinkedBlockingQueue<>());
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      executor.execute(() -> {
         entered.countDown();
         try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
      });
      var owner = new BuildingPreviewGenerationOwner();
      Runnable otherOwner = () -> { };
      try {
         assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
         executor.execute(otherOwner);
         for (int i = 0; i < 1000; i++) owner.start(key(i), () -> null, executor);
         assertEquals(2, executor.getQueue().size());
         owner.cancel();
         assertEquals(List.of(otherOwner), List.copyOf(executor.getQueue()));
         assertFalse(owner.completed());
      } finally {
         owner.cancel(); release.countDown(); executor.shutdownNow();
         assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
      }
   }
   @Test
   void replacingTheKeyCancelsThePreviousGeneration() {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      var tasks = new ArrayDeque<Runnable>();
      var calls = new java.util.ArrayList<Long>();
      owner.start(key(1L), () -> { calls.add(1L); return null; }, tasks::add);
      owner.start(key(2L), () -> { calls.add(2L); return null; }, tasks::add);
      tasks.remove().run();
      assertTrue(calls.isEmpty());
      tasks.remove().run();
      assertEquals(List.of(2L), calls);
   }

   @Test
   void completedResultMustBelongToTheKeyThatStartedItsFuture() throws Exception {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      BuildingBlockResult stale = new BuildingBlockResult(key(1L), Set.of(BlockPos.ZERO));

      owner.start(key(2L), () -> stale, Runnable::run);

      assertTrue(owner.takeCompleted().isEmpty());
      assertFalse(owner.completed());
   }

   @Test
   void changingKeyAfterOldCompletionKeepsTheNewFutureAuthoritative() throws Exception {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      owner.start(key(1L), () -> new BuildingBlockResult(key(1L), Set.of(BlockPos.ZERO)), Runnable::run);
      assertTrue(owner.completed());
      var tasks = new ArrayDeque<Runnable>();
      owner.start(key(2L), () -> new BuildingBlockResult(key(2L), Set.of(new BlockPos(2, 0, 0))), tasks::add);

      assertFalse(owner.completed());
      tasks.remove().run();
      assertEquals(key(2L), owner.takeCompleted().orElseThrow().key());
   }

   @Test
   void failedResultIsConsumedOnceAndDoesNotBlockReplacement() throws Exception {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      var failure = new IllegalStateException("generation failed");
      owner.start(key(1L), () -> { throw failure; }, Runnable::run);
      assertTrue(owner.completed());
      var actual = org.junit.jupiter.api.Assertions.assertThrows(
         java.util.concurrent.ExecutionException.class, owner::takeCompleted);
      org.junit.jupiter.api.Assertions.assertSame(failure, actual.getCause());
      assertFalse(owner.completed());
      assertTrue(owner.takeCompleted().isEmpty());
      BuildingBlockResult current = new BuildingBlockResult(key(2L), Set.of(BlockPos.ZERO));
      owner.start(key(2L), () -> current, Runnable::run);
      assertEquals(current, owner.takeCompleted().orElseThrow());
   }

   @Test
   void lateWorkerCannotPublishIntoReplacementWithTheSameKey() throws Exception {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var exited = new java.util.concurrent.CountDownLatch(1);
      var sharedKey = key(1L);
      owner.start(sharedKey, () -> {
         entered.countDown();
         while (release.getCount() != 0) {
            try { release.await(); } catch (InterruptedException ignored) { }
         }
         return new BuildingBlockResult(sharedKey, Set.of(BlockPos.ZERO));
      }, task -> {
         var worker = new Thread(() -> {
            try { task.run(); } finally { exited.countDown(); }
         });
         worker.setDaemon(true);
         worker.start();
      });
      var tasks = new ArrayDeque<Runnable>();
      var replacement = new BuildingBlockResult(sharedKey, Set.of(new BlockPos(9, 0, 0)));
      try {
         assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
         owner.start(sharedKey, () -> replacement, tasks::add);
      } finally {
         release.countDown();
      }
      assertTrue(exited.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertFalse(owner.completed());
      assertTrue(owner.takeCompleted().isEmpty());
      tasks.remove().run();
      assertEquals(replacement, owner.takeCompleted().orElseThrow());
   }

   @Test
   void currentCompletedResultIsTakenOnce() throws Exception {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      BuildingBlockResult current = new BuildingBlockResult(key(3L), Set.of(new BlockPos(1, 2, 3)));
      owner.start(key(3L), () -> current, Runnable::run);

      assertTrue(owner.completed());
      assertEquals(current, owner.takeCompleted().orElseThrow());
      assertFalse(owner.completed());
      assertTrue(owner.takeCompleted().isEmpty());
   }

   private static BuildingPreviewKey key(long version) {
      return new BuildingPreviewKey(version, List.of(BlockPos.ZERO), false, false);
   }
}
