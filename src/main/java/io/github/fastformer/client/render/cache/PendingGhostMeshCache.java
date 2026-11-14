package io.github.fastformer.client.render.cache;

import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.common.world.BlockPositionSets;
import io.github.fastformer.client.render.model.PendingGhostMesh;
import io.github.fastformer.core.async.LatestOnlyTask;
import java.util.Set;
import java.util.concurrent.CancellationException;
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
   private final LatestOnlyTask<Set<BlockPos>, PendingGhostMesh> generation = new LatestOnlyTask<>();
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
         generation.cancel();
         clearFailure();
         submitCurrentBlocks();
      } else if (this.failed && !generation.pending() && this.clock.getAsLong() - this.retryAtNanos >= 0L) {
         submitCurrentBlocks();
      }
      publishCompletedMesh();
      return this.mesh;
   }

   public void clearPreview() {
      if (this.blocks.isEmpty() && this.mesh.gridEdges().isEmpty() && !generation.pending() && !this.failed) {
         return;
      }
      this.blocks = Set.of();
      this.mesh = PendingGhostMesh.empty();
      generation.cancel();
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
      Set<BlockPos> blocksSnapshot = this.blocks;
      generation.start(blocksSnapshot, () -> this.builder.apply(blocksSnapshot), executor);
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
      generation.drain(completion -> {
         this.mesh = completion.value();
         clearFailure();
      }, failure -> {
         rememberFailure(failure instanceof CancellationException ? null : failure);
         this.mesh = PendingGhostMesh.empty();
      });
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

}
