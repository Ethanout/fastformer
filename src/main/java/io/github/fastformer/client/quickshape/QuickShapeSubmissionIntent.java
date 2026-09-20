package io.github.fastformer.client.quickshape;

import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.geometry.generation.ProgressiveBlockGeneration;
import io.github.fastformer.fastplace.placement.plan.PlacementGeometryPlan;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/** One unsent Enter intent. Only its owning client tick can consume the result. */
public final class QuickShapeSubmissionIntent {
   private Pending pending;

   public boolean begin(long requestId, QuickShapeSubmissionSnapshot snapshot) {
      if (pending != null || requestId <= 0 || snapshot == null) return false;
      pending = new Pending(requestId, snapshot);
      return true;
   }

   public boolean active() { return pending != null; }
   public long requestId() { return pending == null ? 0 : pending.requestId; }
   public QuickShapeSubmissionSnapshot snapshot() { return pending == null ? null : pending.snapshot; }
   public boolean waitingForParameters() { return pending != null && pending.future == null; }

   public void calculate(PlacementGeometryPlan plan, Executor executor) {
      if (!waitingForParameters()) return;
      Pending request = pending;
      request.progress = new ProgressiveBlockGeneration(plan.estimatedTargetBlocks(), false);
      request.future = new FutureTask<>(() -> validate(plan, request.progress));
      try {
         executor.execute(request.future);
      } catch (RuntimeException exception) {
         cancel();
         throw exception;
      }
   }

   public Optional<Completion> takeCompleted() {
      Pending request = pending;
      if (request == null || request.future == null || !request.future.isDone()) return Optional.empty();
      pending = null;
      Outcome outcome;
      try {
         outcome = request.future.get();
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         outcome = Outcome.FAILED;
      } catch (ExecutionException | CancellationException exception) {
         outcome = Outcome.FAILED;
      }
      return Optional.of(new Completion(request.requestId, request.snapshot, outcome));
   }

   public void cancel() {
      Pending request = pending;
      pending = null;
      if (request == null) return;
      if (request.progress != null) request.progress.cancel();
      if (request.future != null) request.future.cancel(true);
   }

   private static Outcome validate(PlacementGeometryPlan plan, ProgressiveBlockGeneration progress) {
      progress.checkCancelled();
      var reservation = WorldOperationMemory.reserveGeneration(plan.estimatedTargetBlocks(), plan.additionalGeneratedBlockSets());
      if (reservation.isEmpty()) return Outcome.MEMORY_UNAVAILABLE;
      try (var memory = reservation.orElseThrow()) {
         var result = plan.generate(progress);
         if (result.status() == BlockGenerationResult.Status.LIMIT_EXCEEDED || result.blocks().size() > plan.maxPlacement()) {
            return Outcome.LIMIT_EXCEEDED;
         }
         return result.successful() && !result.blocks().isEmpty() ? Outcome.READY : Outcome.CONSTRAINTS_FAILED;
      } finally {
         progress.complete();
         progress.releasePublished();
      }
   }

   private static final class Pending {
      final long requestId;
      final QuickShapeSubmissionSnapshot snapshot;
      ProgressiveBlockGeneration progress;
      FutureTask<Outcome> future;

      Pending(long requestId, QuickShapeSubmissionSnapshot snapshot) {
         this.requestId = requestId;
         this.snapshot = snapshot;
      }
   }

   public enum Outcome { READY, LIMIT_EXCEEDED, CONSTRAINTS_FAILED, MEMORY_UNAVAILABLE, FAILED }
   public record Completion(long requestId, QuickShapeSubmissionSnapshot snapshot, Outcome outcome) { }
}
