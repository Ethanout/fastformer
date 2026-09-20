package io.github.fastformer.client.render.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.model.BuildingSpecialBlock;
import io.github.fastformer.fastplace.geometry.ControlPointStyle;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BuildingShellCacheTest {
   @Test
   void rejectedOrCancelledBuildCanRetryTheSameSnapshot() {
      for (int mode = 0; mode < 3; mode++) {
         int failureMode = mode;
         var attempts = new java.util.concurrent.atomic.AtomicInteger();
         var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
               attempts.incrementAndGet();
               if (failureMode == 0) {
                  throw new java.util.concurrent.RejectedExecutionException("temporary rejection");
               }
               var future = new java.util.concurrent.CompletableFuture<T>();
               if (failureMode == 1) future.cancel(false);
               else future.completeExceptionally(new java.util.concurrent.CancellationException());
               return future;
            }
         };
         try {
            BuildingShellCache cache = BuildingShellCache.forImmutableSnapshots(false, executor, ignored -> {});
            Set<BlockPos> blocks = blocksAboveAsyncThreshold(1);
            for (int attempt = 0; attempt < 2; attempt++) {
               assertTrue(cache.mesh(null, null, Map.of(), null, blocks, blocks, Map.of(), false).faces().isEmpty());
            }
            assertEquals(2, attempts.get());
         } finally {
            executor.shutdownNow();
         }
      }
   }

   @Test
   void changedInputPublishesOnlyACompleteReplacement() {
      BuildingShellCache cache = new BuildingShellCache(false);
      Set<BlockPos> originalBlocks = Set.of(BlockPos.ZERO);
      ShapeShellMesh.Mesh original = cache.mesh(null, null, Map.of(), null, originalBlocks, originalBlocks, Map.of(), false);
      assertEquals(6, original.faces().size(), "a single preview block must retain its shell faces");
      Set<BlockPos> largeBlocks = new HashSet<>();
      for (int index = 0; index < 5000; index++) {
         largeBlocks.add(new BlockPos(index, 0, 0));
      }

      ShapeShellMesh.Mesh firstNewFrame = cache.mesh(null, null, Map.of(), null, largeBlocks, largeBlocks, Map.of(), false);

      assertTrue(!firstNewFrame.faces().isEmpty());
      assertTrue(firstNewFrame.faces().size() > original.faces().size());
   }

   @Test
   void asyncReplacementKeepsThePublishedMeshUntilItsCompleteMeshIsReady() throws Exception {
      ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
      try {
         BuildingShellCache cache = BuildingShellCache.forImmutableSnapshots(false, executor, failure -> {
            throw new AssertionError("unexpected mesh failure", failure);
         });
         Set<BlockPos> originalBlocks = Set.of(BlockPos.ZERO);
         ShapeShellMesh.Mesh original = cache.mesh(null, null, Map.of(), null, originalBlocks, originalBlocks, Map.of(), false);
         Set<BlockPos> largeBlocks = blocksAboveAsyncThreshold(1);

         assertSame(original, cache.mesh(null, null, Map.of(), null, largeBlocks, largeBlocks, Map.of(), false));

         ShapeShellMesh.Mesh completed = original;
         long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
         while (completed == original && System.nanoTime() < deadline) {
            Thread.sleep(5L);
            completed = cache.mesh(null, null, Map.of(), null, largeBlocks, largeBlocks, Map.of(), false);
         }
         assertTrue(completed != original);
         assertTrue(!completed.faces().isEmpty());
      } finally {
         executor.shutdownNow();
         assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }
   }

   @Test
   void clearInvalidatesAnAsynchronousMeshBeforeItCanPublish() throws Exception {
      ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
      try {
         BuildingShellCache cache = BuildingShellCache.forImmutableSnapshots(false, executor, failure -> {
            throw new AssertionError("unexpected mesh failure", failure);
         });
         Set<BlockPos> largeBlocks = blocksAboveAsyncThreshold(2);

         cache.mesh(null, null, Map.of(), null, largeBlocks, largeBlocks, Map.of(), false);
         cache.clear();
         Thread.sleep(25L);

         assertEquals(ShapeShellMesh.Mesh.empty(), cache.mesh(null, null, Map.of(), null, Set.of(), Set.of(), Map.of(), false));
      } finally {
         executor.shutdownNow();
         assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }
   }

   @Test
   void failedRebuildDoesNotPublishItsKeyOrReplaceThePreviousMesh() {
      BuildingShellCache cache = new BuildingShellCache(false);
      Set<BlockPos> blocks = Set.of(BlockPos.ZERO);
      ShapeShellMesh.Mesh original = cache.mesh(null, null, Map.of(), null, blocks, blocks, Map.of(), false);
      Map<BlockPos, BuildingSpecialBlock> invalidStyle = Map.of(BlockPos.ZERO, new BuildingSpecialBlock(null, true));

      for (int attempt = 0; attempt < 2; attempt++) {
         assertThrows(NullPointerException.class,
            () -> cache.mesh(null, null, Map.of(), null, blocks, blocks, invalidStyle, false));
      }
      assertSame(original, cache.mesh(null, null, Map.of(), null, blocks, blocks, Map.of(), false));
   }

   @Test
   void failedAsyncBuildForgetsItsInputsInsteadOfReusingTheOldMesh() {
      java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
      BuildingShellCache cache = BuildingShellCache.forImmutableSnapshots(false, failingExecutor(), failure -> failures.incrementAndGet());
      Set<BlockPos> originalBlocks = Set.of(BlockPos.ZERO);
      ShapeShellMesh.Mesh original = cache.mesh(null, null, Map.of(), null, originalBlocks, originalBlocks, Map.of(), false);
      assertTrue(!original.faces().isEmpty());
      Set<BlockPos> asynchronousBlocks = blocksAboveAsyncThreshold(1);

      ShapeShellMesh.Mesh firstFailure = cache.mesh(
         null, null, Map.of(), null, asynchronousBlocks, asynchronousBlocks, Map.of(), false
      );

      assertEquals(1, failures.get());
      assertTrue(firstFailure.faces().isEmpty());
      assertTrue(firstFailure != original, "a failed build must not reuse the previous input set's mesh");

      // The failed input set must be retried, not short-circuited by the identity check.
      cache.mesh(null, null, Map.of(), null, asynchronousBlocks, asynchronousBlocks, Map.of(), false);

      assertEquals(2, failures.get());
   }

   private static ThreadPoolExecutor failingExecutor() {
      return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
         @Override
         public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            java.util.concurrent.CompletableFuture<T> failed = new java.util.concurrent.CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("background mesh failure"));
            return failed;
         }
      };
   }

   @Test
   void controlPointAndRegularBlockDoNotCullEachOther() {
      BuildingShellCache cache = new BuildingShellCache(false);
      BlockPos controlPoint = BlockPos.ZERO;
      BlockPos regular = controlPoint.east();
      Set<BlockPos> blocks = Set.of(controlPoint, regular);

      ShapeShellMesh.Mesh mesh = cache.mesh(
         null,
         null,
         Map.of(),
         null,
         blocks,
         blocks,
         Map.of(controlPoint, new BuildingSpecialBlock(ControlPointStyle.START, true)),
         false
      );

      assertEquals(12, mesh.faces().size());
   }

   private static Set<BlockPos> blocksAboveAsyncThreshold(int y) {
      HashSet<BlockPos> blocks = new HashSet<>();
      for (int x = 0; x <= PreviewAsyncPolicy.SYNCHRONOUS_GRID_LIMIT; x++) {
         blocks.add(new BlockPos(x * 2, y, 0));
      }
      return Set.copyOf(blocks);
   }
}
