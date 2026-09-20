package io.github.fastformer.fastplace.placement.plan;

import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.geometry.generation.ProgressiveBlockGeneration;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.world.MemoryReservation;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;

/** Frozen geometry and effect inputs used by both synchronous and asynchronous generation. */
public record PlacementGenerationPlan(
   List<BlockPos> points,
   FastPlaceGeometry.Modes modes,
   boolean polygonHeightConfirmed,
   PolygonVolumeShape polygonVolumeShape,
   ResolvedPlacementEffect effect,
   long estimatedBlocks,
   PlacementTaskPlan taskPlan
) {
   public PlacementGenerationPlan {
      points = points.stream().map(BlockPos::immutable).toList();
      if (modes == null || polygonVolumeShape == null || taskPlan == null || estimatedBlocks < 0L) {
         throw new IllegalArgumentException("A placement generation plan requires geometry and task policy");
      }
   }

   public long additionalGeneratedBlockSets() {
      // An effect receives a defensive copy and may return a second set. Count
      // that additional live representation during generation admission.
      return this.effect == null || !this.effect.transformsTargets() ? 0L : 1L;
   }

   public long estimatedTargetBlocks() {
      return this.effect == null
         ? this.estimatedBlocks
         : this.effect.estimateTargetBlocks(this.estimatedBlocks);
   }

   public PlacementTask generateAsync() {
      return generateAsync(null);
   }

   public PlacementTask generateAsync(MemoryReservation generationReservation) {
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(this.estimatedTargetBlocks(), false);
      CompletableFuture<BlockGenerationResult> targets;
      try {
         targets = CompletableFuture.supplyAsync(() -> generateTracked(progress));
      } catch (RuntimeException | OutOfMemoryError exception) {
         if (generationReservation != null) {
            generationReservation.close();
         }
         progress.cancel();
         progress.releasePublished();
         throw exception;
      }
      return PlacementTask.generatingResult(targets, progress, this.taskPlan, generationReservation);
   }

   public PlacementTask waitForGenerationMemory() {
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(this.estimatedTargetBlocks(), false);
      return PlacementTask.waitingForGeneration(() -> generateTracked(progress), progress, this.taskPlan,
         estimatedTargetBlocks(), additionalGeneratedBlockSets());
   }

   private BlockGenerationResult generateTracked(ProgressiveBlockGeneration progress) {
      try {
         return generateTargets(progress);
      } finally {
         progress.complete();
         progress.releasePublished();
      }
   }

   public GeneratedPlacement generateNow() {
      return new GeneratedPlacement(generateTargets(null));
   }

   private BlockGenerationResult generateTargets(ProgressiveBlockGeneration progress) {
      return new PlacementGeometryPlan(points, modes, polygonHeightConfirmed, polygonVolumeShape,
         taskPlan.maxPlacement(), effect).generate(progress == null
            ? io.github.fastformer.fastplace.geometry.generation.BlockGenerationObserver.NONE : progress);
   }

   public record GeneratedPlacement(BlockGenerationResult result) {
      public GeneratedPlacement {
         if (result == null) {
            throw new IllegalArgumentException("Generated placement requires a result");
         }
      }
   }
}
