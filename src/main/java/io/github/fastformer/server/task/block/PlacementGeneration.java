package io.github.fastformer.server.task.block;

import io.github.fastformer.common.geometry.generation.source.BlockGenerationResult;
import io.github.fastformer.common.geometry.generation.source.ProgressiveBlockGeneration;
import io.github.fastformer.common.world.memory.MemoryAdmission;
import io.github.fastformer.common.world.memory.MemoryReservation;
import io.github.fastformer.common.world.memory.WorldOperationMemory;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/** Owns asynchronous generation and its budget until completion or worker-owned cleanup. */
final class PlacementGeneration {
   enum Outcome { PENDING, READY, MEMORY_REJECTED, FAILED }
   private CompletableFuture<BlockGenerationResult> future;
   private Deferred deferred;
   private MemoryReservation reservation;
   private final ProgressiveBlockGeneration progress;
   private final Executor executor;
   private BlockGenerationResult result;
   private boolean throttled;
   private String failure;

   PlacementGeneration(CompletableFuture<BlockGenerationResult> future, ProgressiveBlockGeneration progress,
      MemoryReservation reservation) {
      this(future, progress, reservation, ForkJoinPool.commonPool());
   }

   PlacementGeneration(CompletableFuture<BlockGenerationResult> future, ProgressiveBlockGeneration progress,
      MemoryReservation reservation, Executor executor) {
      this.future = future;
      this.progress = progress;
      this.reservation = reservation;
      this.executor = Objects.requireNonNull(executor, "executor");
   }

   void defer(Supplier<BlockGenerationResult> generator, long estimatedBlocks, long additionalBlockSets) {
      if (future != null || deferred != null) throw new IllegalStateException("Generation already owns work");
      deferred = new Deferred(Objects.requireNonNull(generator), estimatedBlocks, additionalBlockSets);
   }

   boolean waitingForMemory() { return deferred != null; }
   boolean throttled() { return throttled; }
   String failure() { return failure; }

   Outcome poll() {
      if (deferred != null) {
         MemoryAdmission admission = WorldOperationMemory.generationAdmission(deferred.blocks(), deferred.additionalSets());
         throttled = admission.throttled();
         if (!admission.allowed()) { deferred = null; return Outcome.MEMORY_REJECTED; }
         reservation = WorldOperationMemory.reserve(admission).orElse(null);
         if (reservation == null) return Outcome.PENDING;
         Deferred accepted = deferred;
         deferred = null;
         try { future = CompletableFuture.supplyAsync(accepted.generator(), executor); }
         catch (RuntimeException | OutOfMemoryError rejected) {
            releaseReservation(null);
            failure = "generation scheduling: " + rejected.getClass().getSimpleName();
            return Outcome.FAILED;
         }
      }
      if (future == null) {
         if (failure != null) return Outcome.FAILED;
         throw new IllegalStateException("Generation has no pending result");
      }
      if (!future.isDone()) return Outcome.PENDING;
      try { result = Objects.requireNonNull(future.join(), "generation result"); }
      catch (RuntimeException | OutOfMemoryError rejected) {
         future = null;
         releaseReservation(null);
         releaseProgress();
         failure = "generation future: " + rejected.getClass().getSimpleName();
         return Outcome.FAILED;
      }
      future = null;
      releaseProgress();
      return Outcome.READY;
   }

   BlockGenerationResult takeResult() {
      BlockGenerationResult ready = Objects.requireNonNull(result, "Generation is not ready");
      result = null;
      return ready;
   }

   MemoryReservation takeReservation() {
      MemoryReservation transferred = reservation;
      reservation = null;
      return transferred;
   }

   ProgressiveBlockGeneration.Snapshot snapshot() { return progress == null ? null : progress.snapshot(); }

   /** A running worker keeps its budget; cancellation removes only the task's observation. */
   void cancel() {
      deferred = null;
      result = null;
      if (progress != null) progress.cancel();
      releaseProgress();
      CompletableFuture<BlockGenerationResult> worker = future;
      future = null;
      releaseReservation(worker);
   }

   private void releaseProgress() { if (progress != null) progress.releasePublished(); }

   private void releaseReservation(CompletableFuture<?> worker) {
      MemoryReservation owned = reservation;
      reservation = null;
      if (owned == null) return;
      if (worker == null || worker.isDone()) owned.close();
      else worker.whenComplete((ignored, failure) -> owned.close());
   }

   private record Deferred(Supplier<BlockGenerationResult> generator, long blocks, long additionalSets) {}
}
