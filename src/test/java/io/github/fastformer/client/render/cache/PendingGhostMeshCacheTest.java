package io.github.fastformer.client.render.cache;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.render.PendingPreviewGrid;
import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.client.render.model.PendingGhostMesh;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PendingGhostMeshCacheTest {
   private static final PendingGhostMesh OLD_MESH = meshAt(0);
   private static final PendingGhostMesh NEW_MESH = meshAt(1);

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
