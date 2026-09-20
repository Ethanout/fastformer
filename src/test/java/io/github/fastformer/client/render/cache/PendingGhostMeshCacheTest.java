package io.github.fastformer.client.render.cache;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.render.PendingPreviewGrid;
import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.client.render.model.PendingGhostMesh;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PendingGhostMeshCacheTest {
   private static final PendingGhostMesh OLD_MESH = meshAt(0);
   private static final PendingGhostMesh NEW_MESH = meshAt(1);

   @Test
   void cancellationRetriesAcrossTheNanoTimeWrapWithoutLogging() throws Exception {
      ThreadPoolExecutor executor = executor();
      AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 10L);
      AtomicInteger attempts = new AtomicInteger();
      try {
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, ignored -> {
            if (attempts.incrementAndGet() == 1) {
               throw new java.util.concurrent.CancellationException();
            }
            return NEW_MESH;
         }, failOnError(), clock::get);
         Set<BlockPos> blocks = Set.of(BlockPos.ZERO);
         assertEquals(PendingGhostMesh.empty(), cache.mesh(blocks));
         assertEquals(PendingGhostMesh.empty(), cache.mesh(blocks));
         assertEquals(1, attempts.get());
         clock.addAndGet(PendingGhostMeshCache.INITIAL_RETRY_DELAY_NANOS);
         assertEquals(NEW_MESH, cache.mesh(blocks));
         assertEquals(2, attempts.get());
      } finally {
         stop(executor);
      }
   }

   @Test
   void asyncReplacementHidesThePublishedOldMesh() throws Exception {
      ThreadPoolExecutor executor = executor();
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      try {
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, blocks -> {
            if (blocks.size() == 1) return OLD_MESH;
            started.countDown();
            awaitIgnoringInterrupt(release);
            return NEW_MESH;
         }, failOnError());
         Set<BlockPos> oldBlocks = Set.of(BlockPos.ZERO);
         Set<BlockPos> newBlocks = asynchronousBlocks(1);

         assertEquals(OLD_MESH, cache.mesh(oldBlocks));
         assertEquals(PendingGhostMesh.empty(), cache.mesh(newBlocks));
         assertTrue(started.await(2, TimeUnit.SECONDS));

         release.countDown();
         assertEquals(NEW_MESH, awaitMesh(cache, newBlocks));
      } finally {
         release.countDown();
         stop(executor);
      }
   }

   @Test
   void replacementRemovesOnlyItsCancelledQueuedTask() throws Exception {
      ThreadPoolExecutor executor = executor();
      CountDownLatch workerStarted = new CountDownLatch(1);
      CountDownLatch releaseWorker = new CountDownLatch(1);
      try {
         executor.execute(() -> {
            workerStarted.countDown();
            awaitIgnoringInterrupt(releaseWorker);
         });
         assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, ignored -> NEW_MESH, failOnError());
         cache.mesh(asynchronousBlocks(10));
         Future<?> sentinel = executor.submit(() -> {});

         cache.mesh(asynchronousBlocks(20));

         assertEquals(2, executor.getQueue().size());
         assertTrue(executor.getQueue().contains(sentinel));
      } finally {
         releaseWorker.countDown();
         stop(executor);
      }
   }

   @Test
   void lateCancelledResultCannotReplaceTheNewMesh() throws Exception {
      ThreadPoolExecutor executor = executor();
      CountDownLatch oldStarted = new CountDownLatch(1);
      CountDownLatch releaseOld = new CountDownLatch(1);
      try {
         Set<BlockPos> oldBlocks = asynchronousBlocks(30);
         Set<BlockPos> newBlocks = asynchronousBlocks(40);
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, blocks -> {
            if (blocks.contains(new BlockPos(0, 30, 0))) {
               oldStarted.countDown();
               awaitIgnoringInterrupt(releaseOld);
               return OLD_MESH;
            }
            return NEW_MESH;
         }, failOnError());
         cache.mesh(oldBlocks);
         assertTrue(oldStarted.await(2, TimeUnit.SECONDS));

         cache.mesh(newBlocks);
         releaseOld.countDown();

         assertEquals(NEW_MESH, awaitMesh(cache, newBlocks));
      } finally {
         releaseOld.countDown();
         stop(executor);
      }
   }

   @Test
   void clearKeepsALateCancelledResultInvisible() throws Exception {
      ThreadPoolExecutor executor = executor();
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(1);
      try {
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, ignored -> {
            started.countDown();
            awaitIgnoringInterrupt(release);
            finished.countDown();
            return OLD_MESH;
         }, failOnError());
         cache.mesh(asynchronousBlocks(50));
         assertTrue(started.await(2, TimeUnit.SECONDS));

         cache.clearPreview();
         release.countDown();

         assertTrue(finished.await(2, TimeUnit.SECONDS));
         assertEquals(PendingGhostMesh.empty(), cache.mesh(Set.of()));
      } finally {
         release.countDown();
         stop(executor);
      }
   }

   @Test
   void aFailedAsyncBuildRetriesTheSameInputAfterBackoff() throws Exception {
      ThreadPoolExecutor executor = executor();
      AtomicLong clock = new AtomicLong();
      AtomicInteger attempts = new AtomicInteger();
      List<Throwable> failures = new ArrayList<>();
      CountDownLatch firstFailed = new CountDownLatch(1);
      Set<BlockPos> blocks = asynchronousBlocks(60);
      try {
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, ignored -> {
            if (attempts.incrementAndGet() == 1) {
               firstFailed.countDown();
               throw new IllegalStateException("temporary mesh failure");
            }
            return NEW_MESH;
         }, failures::add, clock::get);

         cache.mesh(blocks);
         assertTrue(firstFailed.await(2, TimeUnit.SECONDS));
         assertEquals(PendingGhostMesh.empty(), awaitEmptyThen(cache, blocks, firstFailed));
         assertEquals(1, attempts.get());
         assertEquals(1, failures.size());

         cache.mesh(blocks);
         assertEquals(1, attempts.get(), "a retry must wait for the backoff");

         clock.addAndGet(PendingGhostMeshCache.INITIAL_RETRY_DELAY_NANOS);
         assertEquals(NEW_MESH, awaitMesh(cache, blocks));
         assertEquals(2, attempts.get());
         assertEquals(1, failures.size());
      } finally {
         stop(executor);
      }
   }

   @Test
   void aRepeatedFailureStaysQuietUntilTheBackoffElapses() throws Exception {
      ThreadPoolExecutor executor = executor();
      AtomicLong clock = new AtomicLong();
      AtomicInteger attempts = new AtomicInteger();
      List<Throwable> failures = new ArrayList<>();
      Set<BlockPos> blocks = asynchronousBlocks(70);
      try {
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, ignored -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("still failing");
         }, failures::add, clock::get);

         cache.mesh(blocks);
         awaitFailureCount(cache, blocks, failures, 1);
         cache.mesh(blocks);
         cache.mesh(blocks);
         assertEquals(1, attempts.get());
         assertEquals(1, failures.size());

         clock.addAndGet(PendingGhostMeshCache.INITIAL_RETRY_DELAY_NANOS);
         cache.mesh(blocks);
         awaitSettled(cache, blocks);
         assertEquals(2, attempts.get());
         assertEquals(1, failures.size());
         cache.mesh(blocks);
         assertEquals(2, attempts.get());
      } finally {
         stop(executor);
      }
   }

   @Test
   void clearDropsAFailedInputSoALateResultStaysInvisible() throws Exception {
      ThreadPoolExecutor executor = executor();
      AtomicLong clock = new AtomicLong();
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(1);
      List<Throwable> failures = new ArrayList<>();
      Set<BlockPos> blocks = asynchronousBlocks(80);
      try {
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, ignored -> {
            started.countDown();
            awaitIgnoringInterrupt(release);
            finished.countDown();
            throw new IllegalStateException("late failure");
         }, failures::add, clock::get);

         cache.mesh(blocks);
         assertTrue(started.await(2, TimeUnit.SECONDS));
         cache.clearPreview();
         release.countDown();
         assertTrue(finished.await(2, TimeUnit.SECONDS));

         assertEquals(PendingGhostMesh.empty(), cache.mesh(Set.of()));
         assertEquals(0, failures.size());
      } finally {
         release.countDown();
         stop(executor);
      }
   }

   @Test
   void aNewInputDoesNotPublishAFailedRetryFromTheOldInput() throws Exception {
      ThreadPoolExecutor executor = executor();
      AtomicLong clock = new AtomicLong();
      AtomicInteger attempts = new AtomicInteger();
      List<Throwable> failures = new ArrayList<>();
      Set<BlockPos> oldBlocks = asynchronousBlocks(90);
      Set<BlockPos> newBlocks = asynchronousBlocks(91);
      try {
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, blocks -> {
            int attempt = attempts.incrementAndGet();
            if (blocks.contains(new BlockPos(0, 90, 0))) {
               throw new IllegalStateException("old input failed");
            }
            return NEW_MESH;
         }, failures::add, clock::get);

         cache.mesh(oldBlocks);
         awaitFailureCount(cache, oldBlocks, failures, 1);
         assertEquals(PendingGhostMesh.empty(), cache.mesh(oldBlocks));

         assertEquals(NEW_MESH, awaitMesh(cache, newBlocks));
         clock.addAndGet(PendingGhostMeshCache.MAX_RETRY_DELAY_NANOS);
         assertEquals(NEW_MESH, cache.mesh(newBlocks));
         assertEquals(2, attempts.get());
      } finally {
         stop(executor);
      }
   }

   @Test
   void aSynchronousBuilderFailureRetriesTheSameInputAfterBackoff() {
      AtomicLong clock = new AtomicLong();
      AtomicInteger attempts = new AtomicInteger();
      List<Throwable> failures = new ArrayList<>();
      Set<BlockPos> blocks = Set.of(BlockPos.ZERO);
      PendingGhostMeshCache cache = new PendingGhostMeshCache(executor(), ignored -> {
         if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("sync failure");
         }
         return NEW_MESH;
      }, failures::add, clock::get);

      assertEquals(PendingGhostMesh.empty(), cache.mesh(blocks));
      assertEquals(1, attempts.get());
      assertEquals(PendingGhostMesh.empty(), cache.mesh(blocks));
      assertEquals(1, attempts.get());

      clock.addAndGet(PendingGhostMeshCache.INITIAL_RETRY_DELAY_NANOS);
      assertEquals(NEW_MESH, cache.mesh(blocks));
      assertEquals(2, attempts.get());
      assertEquals(1, failures.size());
   }

   @Test
   void aRejectedSubmitDoesNotCacheTheInputAsSuccess() {
      ThreadPoolExecutor executor = executor();
      executor.shutdown();
      AtomicLong clock = new AtomicLong();
      List<Throwable> failures = new ArrayList<>();
      Set<BlockPos> blocks = asynchronousBlocks(100);
      PendingGhostMeshCache cache = new PendingGhostMeshCache(
         executor, ignored -> NEW_MESH, failures::add, clock::get
      );

      assertEquals(PendingGhostMesh.empty(), cache.mesh(blocks));
      assertEquals(1, failures.size());
      assertEquals(PendingGhostMesh.empty(), cache.mesh(blocks));
      assertEquals(1, failures.size());

      clock.addAndGet(PendingGhostMeshCache.INITIAL_RETRY_DELAY_NANOS);
      assertEquals(PendingGhostMesh.empty(), cache.mesh(blocks));
      assertEquals(1, failures.size());
   }

   @Test
   void anEmptyMeshFromTheBuilderIsNotRetried() throws Exception {
      ThreadPoolExecutor executor = executor();
      AtomicLong clock = new AtomicLong();
      AtomicInteger attempts = new AtomicInteger();
      Set<BlockPos> blocks = asynchronousBlocks(110);
      try {
         PendingGhostMeshCache cache = new PendingGhostMeshCache(executor, ignored -> {
            attempts.incrementAndGet();
            return PendingGhostMesh.empty();
         }, failOnError(), clock::get);

         assertEquals(PendingGhostMesh.empty(), awaitSettled(cache, blocks));
         clock.addAndGet(PendingGhostMeshCache.MAX_RETRY_DELAY_NANOS);
         assertEquals(PendingGhostMesh.empty(), cache.mesh(blocks));
         assertEquals(1, attempts.get());
      } finally {
         stop(executor);
      }
   }

   private static PendingGhostMesh awaitEmptyThen(
      PendingGhostMeshCache cache, Set<BlockPos> blocks, CountDownLatch alreadyTriggered
   ) throws InterruptedException {
      assertEquals(0, alreadyTriggered.getCount());
      return awaitSettled(cache, blocks);
   }

   private static void awaitFailureCount(
      PendingGhostMeshCache cache, Set<BlockPos> blocks, List<Throwable> failures, int expected
   ) throws InterruptedException {
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      do {
         cache.mesh(blocks);
         if (failures.size() >= expected) {
            return;
         }
         Thread.sleep(5L);
      } while (System.nanoTime() < deadline);
      assertEquals(expected, failures.size());
   }

   private static PendingGhostMesh awaitSettled(PendingGhostMeshCache cache, Set<BlockPos> blocks)
         throws InterruptedException {
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      PendingGhostMesh mesh;
      do {
         mesh = cache.mesh(blocks);
         Thread.sleep(5L);
      } while (System.nanoTime() < deadline && mesh == PendingGhostMesh.empty() && cacheHasInFlightWork(cache));
      return cache.mesh(blocks);
   }

   private static boolean cacheHasInFlightWork(PendingGhostMeshCache cache) {
      try {
         var field = PendingGhostMeshCache.class.getDeclaredField("future");
         field.setAccessible(true);
         return field.get(cache) != null;
      } catch (ReflectiveOperationException exception) {
         throw new AssertionError(exception);
      }
   }

   private static Set<BlockPos> asynchronousBlocks(int y) {
      HashSet<BlockPos> blocks = new HashSet<>();
      for (int x = 0; x <= PreviewAsyncPolicy.SYNCHRONOUS_GRID_LIMIT; x++) {
         blocks.add(new BlockPos(x, y, 0));
      }
      return Set.copyOf(blocks);
   }

   private static PendingGhostMesh awaitMesh(PendingGhostMeshCache cache, Set<BlockPos> blocks)
         throws InterruptedException {
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      PendingGhostMesh mesh;
      do {
         mesh = cache.mesh(blocks);
         if (!mesh.gridEdges().isEmpty()) return mesh;
         Thread.sleep(5L);
      } while (System.nanoTime() < deadline);
      return mesh;
   }

   private static java.util.function.Consumer<Throwable> failOnError() {
      return failure -> fail("unexpected mesh failure", failure);
   }

   private static PendingGhostMesh meshAt(int x) {
      return new PendingGhostMesh(java.util.List.of(PendingPreviewGrid.Segment.of(
         new PendingPreviewGrid.Point(x, 0, 0), new PendingPreviewGrid.Point(x + 1, 0, 0)
      )));
   }

   private static ThreadPoolExecutor executor() {
      return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
   }

   private static void stop(ThreadPoolExecutor executor) throws InterruptedException {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
   }

   private static void awaitIgnoringInterrupt(CountDownLatch latch) {
      boolean interrupted = false;
      while (true) {
         try {
            latch.await();
            break;
         } catch (InterruptedException ignored) {
            interrupted = true;
         }
      }
      if (interrupted) Thread.currentThread().interrupt();
   }
}
