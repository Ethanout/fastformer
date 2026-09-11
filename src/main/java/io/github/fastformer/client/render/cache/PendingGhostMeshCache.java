package io.github.fastformer.client.render.cache;

import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.client.render.model.PendingGhostMesh;
import io.github.fastformer.client.render.model.PendingMeshResult;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;

/** Owns asynchronous mesh generation for the pending preview grid. */
public final class PendingGhostMeshCache {
   private final ThreadPoolExecutor executor;
   private final Function<Set<BlockPos>, PendingGhostMesh> builder;
   private final Consumer<Throwable> failureLogger;
   private Set<BlockPos> blocks = Set.of();
   private PendingGhostMesh mesh = PendingGhostMesh.empty();
   private Future<PendingMeshResult> future;
   private long version;

   public PendingGhostMeshCache(
      ThreadPoolExecutor executor,
      Function<Set<BlockPos>, PendingGhostMesh> builder,
      Consumer<Throwable> failureLogger
   ) {
      this.executor = executor;
      this.builder = builder;
      this.failureLogger = failureLogger;
   }

   public PendingGhostMesh mesh(Set<BlockPos> requestedBlocks) {
      if (this.blocks != requestedBlocks && !this.blocks.equals(requestedBlocks)) {
         this.blocks = Set.copyOf(requestedBlocks);
         this.version++;
         cancelFuture();
         if (PreviewAsyncPolicy.meshSynchronously(this.blocks.size())) {
            this.mesh = this.builder.apply(this.blocks);
         } else {
            this.mesh = PendingGhostMesh.empty();
            long requestedVersion = this.version;
            Set<BlockPos> blocksSnapshot = this.blocks;
            this.future = this.executor.submit(
               () -> new PendingMeshResult(requestedVersion, this.builder.apply(blocksSnapshot))
            );
         }
      }
      publishCompletedMesh();
      return this.mesh;
   }

   public void clearPreview() {
      if (this.blocks.isEmpty() && this.mesh.gridEdges().isEmpty() && this.future == null) {
         return;
      }
      this.blocks = Set.of();
      this.mesh = PendingGhostMesh.empty();
      this.version++;
      cancelFuture();
   }

   public void clear() {
      clearPreview();
   }

   private void publishCompletedMesh() {
      if (this.future == null || !this.future.isDone()) {
         return;
      }
      try {
         PendingMeshResult result = this.future.get();
         if (result.version() == this.version) {
            this.mesh = result.mesh();
         }
      } catch (CancellationException ignored) {
         // A newer preview superseded this mesh.
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
      } catch (java.util.concurrent.ExecutionException exception) {
         if (!(exception.getCause() instanceof CancellationException)) {
            this.failureLogger.accept(exception.getCause());
            this.mesh = PendingGhostMesh.empty();
         }
      } finally {
         this.future = null;
      }
   }

   private void cancelFuture() {
      if (this.future != null) {
         Future<PendingMeshResult> cancelled = this.future;
         this.future = null;
         cancelled.cancel(true);
         if (cancelled instanceof Runnable task) {
            this.executor.remove(task);
         }
      }
   }
}
