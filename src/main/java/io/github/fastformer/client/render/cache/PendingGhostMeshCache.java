package io.github.fastformer.client.render.cache;

import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.fastplace.geometry.BlockPositionSets;
import io.github.fastformer.client.render.model.PendingGhostMesh;
import io.github.fastformer.client.render.model.PendingMeshResult;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;

/** Owns asynchronous mesh generation for the pending preview grid. */
public final class PendingGhostMeshCache {
   static final long INITIAL_RETRY_DELAY_NANOS = 50_000_000L;
   static final long MAX_RETRY_DELAY_NANOS = 800_000_000L;

   private final ThreadPoolExecutor executor;
   private final Function<Set<BlockPos>, PendingGhostMesh> builder;
   private final Consumer<Throwable> failureLogger;
   private final LongSupplier clock;
   private Set<BlockPos> blocks = Set.of();
   private PendingGhostMesh mesh = PendingGhostMesh.empty();
   private Future<PendingMeshResult> future;
   private long version;
   private boolean failed;
   private long retryAtNanos;
   private long retryDelayNanos = INITIAL_RETRY_DELAY_NANOS;

   public PendingGhostMeshCache(
      ThreadPoolExecutor executor,
      Function<Set<BlockPos>, PendingGhostMesh> builder,
      Consumer<Throwable> failureLogger
   ) {
      this(executor, builder, failureLogger, System::nanoTime);
   }

   public PendingGhostMeshCache(
      ThreadPoolExecutor executor,
      Function<Set<BlockPos>, PendingGhostMesh> builder,
      Consumer<Throwable> failureLogger,
      LongSupplier clock
   ) {
      this.executor = executor;
      this.builder = builder;
      this.failureLogger = failureLogger;
      this.clock = clock;
   }

   public PendingGhostMesh mesh(Set<BlockPos> requestedBlocks) {
      if (this.blocks != requestedBlocks && !this.blocks.equals(requestedBlocks)) {
         rememberRequestedBlocks(requestedBlocks);
         this.version++;
         cancelFuture();
         clearFailure();
         submitCurrentBlocks();
      } else if (this.failed && this.future == null && this.clock.getAsLong() - this.retryAtNanos >= 0L) {
         submitCurrentBlocks();
      }
      publishCompletedMesh();
      return this.mesh;
   }

   public void clearPreview() {
      if (this.blocks.isEmpty() && this.mesh.gridEdges().isEmpty() && this.future == null && !this.failed) {
         return;
      }
      this.blocks = Set.of();
      this.mesh = PendingGhostMesh.empty();
      this.version++;
      cancelFuture();
      clearFailure();
   }

   public void clear() {
      clearPreview();
   }

   private void rememberRequestedBlocks(Set<BlockPos> requestedBlocks) {
      this.blocks = BlockPositionSets.copyOf(requestedBlocks);
   }

   private void submitCurrentBlocks() {
      if (PreviewAsyncPolicy.meshSynchronously(this.blocks.size())) {
         buildSynchronously();
         return;
      }
      this.mesh = PendingGhostMesh.empty();
      long requestedVersion = this.version;
      Set<BlockPos> blocksSnapshot = this.blocks;
      try {
         this.future = this.executor.submit(
            () -> new PendingMeshResult(requestedVersion, this.builder.apply(blocksSnapshot))
         );
      } catch (RejectedExecutionException exception) {
         rememberFailure(exception);
      }
   }

   private void buildSynchronously() {
      try {
         this.mesh = this.builder.apply(this.blocks);
         clearFailure();
      } catch (CancellationException ignored) {
         rememberFailure(null);
         this.mesh = PendingGhostMesh.empty();
      } catch (RuntimeException exception) {
         rememberFailure(exception);
         this.mesh = PendingGhostMesh.empty();
      }
   }

   private void publishCompletedMesh() {
      if (this.future == null || !this.future.isDone()) {
         return;
      }
      try {
         PendingMeshResult result = this.future.get();
         if (result.version() == this.version) {
            this.mesh = result.mesh();
            clearFailure();
         }
      } catch (CancellationException ignored) {
         rememberFailure(null);
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
      } catch (java.util.concurrent.ExecutionException exception) {
         Throwable cause = exception.getCause();
         if (cause instanceof CancellationException) {
            rememberFailure(null);
            return;
         }
         rememberFailure(cause);
         this.mesh = PendingGhostMesh.empty();
      } finally {
         this.future = null;
      }
   }

   private void rememberFailure(Throwable failure) {
      boolean firstFailure = !this.failed;
      this.failed = true;
      this.retryAtNanos = this.clock.getAsLong() + this.retryDelayNanos;
      this.retryDelayNanos = Math.min(this.retryDelayNanos * 2L, MAX_RETRY_DELAY_NANOS);
      if (firstFailure && failure != null) {
         this.failureLogger.accept(failure);
      }
   }

   private void clearFailure() {
      this.failed = false;
      this.retryAtNanos = 0L;
      this.retryDelayNanos = INITIAL_RETRY_DELAY_NANOS;
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
