package io.github.fastformer.client.render.core;

import static io.github.fastformer.client.render.core.PreviewRenderResources.BUILDING_SHELL_BLOCKS_CACHE;
import static io.github.fastformer.client.render.core.PreviewRenderResources.PREVIEW_GENERATION_EXECUTOR;

import io.github.fastformer.client.controlpoint.ControlPointPresentation;
import io.github.fastformer.client.placement.effect.PlacementEffectPreview;
import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.client.render.geometry.PreviewGeometrySupport;
import io.github.fastformer.client.render.model.BuildingBlockResult;
import io.github.fastformer.client.render.model.BuildingPreviewKey;
import io.github.fastformer.client.render.model.BuildingRenderFrame;
import io.github.fastformer.client.render.model.BuildingRenderKey;
import io.github.fastformer.client.render.model.BuildingRenderLayers;
import io.github.fastformer.client.render.model.BuildingSpecialBlock;
import io.github.fastformer.client.render.state.ClientPreviewState;
import io.github.fastformer.fastplace.geometry.FillMode;
import io.github.fastformer.fastplace.geometry.GeometryPreviewBlocks;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPoint;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.fastplace.geometry.generation.GenerationLimitExceeded;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.geometry.generation.ProgressiveBlockGeneration;
import io.github.fastformer.fastplace.geometry.generation.WallGenerator;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.QuickShapeStage;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Owns building generation results and render frames for one preview session. */
final class BuildingPreviewCache {
   private final ClientPreviewState previewState;
   private final java.util.function.BooleanSupplier modifierHeld;
   private final java.util.function.BiConsumer<String, Throwable> failureLogger;

   BuildingPreviewCache(ClientPreviewState previewState,
      java.util.function.BooleanSupplier modifierHeld,
      java.util.function.BiConsumer<String, Throwable> failureLogger) {
      this.previewState = previewState;
      this.modifierHeld = modifierHeld;
      this.failureLogger = failureLogger;
   }

   private BuildingPreviewPayload cachedConfirmedBuildingState;
   private LineTieBias cachedConfirmedBuildingBias = LineTieBias.DEFAULT;
   private Set<BlockPos> cachedConfirmedBuildingBlocks = Set.of();
   private BuildingPreviewKey cachedBuildingPreviewKey;
   private Set<BlockPos> cachedBuildingPreviewBlocks = Set.of();
   private Set<BlockPos> cachedBuildingFallbackBlocks = Set.of();
   private final BuildingPreviewGenerationOwner generation =
      new BuildingPreviewGenerationOwner();
   private ProgressiveBlockGeneration cachedBuildingPreviewProgress;
   private boolean cachedBuildingPreviewAtLimit;
   private long cachedBuildingPreviewResultVersion;
   private BuildingRenderKey cachedBuildingRenderKey;
   private BuildingRenderFrame cachedBuildingRenderFrame = BuildingRenderFrame.empty();
   void resetBuildingPreviewCaches() {
      cachedConfirmedBuildingState = null;
      cachedConfirmedBuildingBias = LineTieBias.DEFAULT;
      cachedConfirmedBuildingBlocks = Set.of();
      cachedBuildingPreviewKey = null;
      cachedBuildingPreviewBlocks = Set.of();
      cancelBuildingPreviewGeneration();
      cachedBuildingRenderKey = null;
      cachedBuildingRenderFrame = BuildingRenderFrame.empty();
      clearBuildingShellCaches();
   }

   private void clearBuildingShellCaches() {
      PreviewRenderResources.clearShells();
   }

   private Set<BlockPos> confirmedBuildingBlocks(BuildingPreviewPayload snapshot) {
      LineTieBias effectiveBias = effectiveBuildingModes(snapshot).faceTieBias();
      if (cachedConfirmedBuildingState != snapshot || cachedConfirmedBuildingBias != effectiveBias) {
         cachedConfirmedBuildingState = snapshot;
         cachedConfirmedBuildingBias = effectiveBias;
         FastPlaceGeometry.Modes modes = effectiveBuildingModes(snapshot);
         try {
            if (!PreviewAsyncPolicy.shouldRenderSolidFallback(snapshot.points(),
               buildingPreviewWorkload(snapshot, snapshot.points(), snapshot.polygonHeightConfirmed()))) {
               cachedConfirmedBuildingBlocks = buildingPreviewLimitFallbackSafely(
                  snapshot, snapshot.points(), snapshot.polygonHeightConfirmed(), modes
               );
               return cachedConfirmedBuildingBlocks;
            }
            Set<BlockPos> generated = buildingPreviewBlocks(
               snapshot,
               snapshot.points(),
               snapshot.polygonHeightConfirmed(),
               modes
            );
            cachedConfirmedBuildingBlocks = completedBuildingPreview(
               snapshot, snapshot.points(), snapshot.polygonHeightConfirmed(), modes, generated
            );
         } catch (RuntimeException exception) {
            failureLogger.accept("Unable to generate FastFormer confirmed building preview; using outline fallback", exception);
            cachedConfirmedBuildingBlocks = buildingPreviewLimitFallbackSafely(
               snapshot, snapshot.points(), snapshot.polygonHeightConfirmed(), modes
            );
         }
      }
      return cachedConfirmedBuildingBlocks;
   }

   private Set<BlockPos> buildingPreviewBlocks(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed
   ) {
      return buildingPreviewBlocks(snapshot, points, polygonHeightConfirmed, effectiveBuildingModes(snapshot));
   }

   private Set<BlockPos> buildingPreviewBlocks(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes
   ) {
      return buildingPreviewBlocks(snapshot, points, polygonHeightConfirmed, modes, null);
   }

   private Set<BlockPos> buildingPreviewBlocks(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes,
      ProgressiveBlockGeneration progress
   ) {
      return buildingPreviewBlocks(snapshot, points, polygonHeightConfirmed, modes, progress, FastPlaceGeometry.PREVIEW_MAX_BLOCKS);
   }

   private Set<BlockPos> buildingPreviewBlocks(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes, ProgressiveBlockGeneration progress, int blockLimit
   ) {
      return snapshot.faceMode() == FaceMode.POLYGON && !snapshot.polygonClosed()
         ? WallGenerator.generate(
            points,
            false,
            BlockPos.ZERO,
            blockLimit,
            progress == null ? io.github.fastformer.fastplace.geometry.generation.BlockGenerationObserver.NONE : progress
         )
         : FastPlaceGeometry.blocks(
            points,
            modes,
            polygonHeightConfirmed,
            snapshot.polygonVolumeShape(),
            blockLimit,
            progress == null ? io.github.fastformer.fastplace.geometry.generation.BlockGenerationObserver.NONE : progress
          );
   }

   Set<BlockPos> buildingPreviewBlocksCached(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed
   ) {
      long buildingVersion = previewState.buildingVersion();
      boolean modifierHeld = this.modifierHeld.getAsBoolean();
      BuildingPreviewKey key = cachedBuildingPreviewKey;
      if (key == null
         || key.stateVersion() != buildingVersion
         || key.heightConfirmed() != polygonHeightConfirmed
         || key.modifierHeld() != modifierHeld
         || !key.points().equals(points)) {
         key = new BuildingPreviewKey(buildingVersion, points, polygonHeightConfirmed, modifierHeld);
      }
      if (!key.equals(cachedBuildingPreviewKey)) {
         cachedBuildingPreviewKey = key;
         cachedBuildingFallbackBlocks = Set.of();
         cachedBuildingPreviewAtLimit = false;
         cachedBuildingPreviewResultVersion++;
         if (cachedBuildingPreviewProgress != null) {
            cachedBuildingPreviewProgress.cancel();
            cachedBuildingPreviewProgress = null;
         }
         generation.cancel();
         FastPlaceGeometry.Modes modes = effectiveBuildingModes(snapshot);
         PreviewAsyncPolicy.Workload workload = buildingPreviewWorkload(snapshot, key.points(), polygonHeightConfirmed);
         if (!PreviewAsyncPolicy.shouldRenderSolidFallback(key.points(), workload)) {
            cachedBuildingPreviewBlocks = buildingPreviewLimitFallbackSafely(
               snapshot, key.points(), polygonHeightConfirmed, modes
            );
         } else if (PreviewAsyncPolicy.generateSynchronously(key.points(), workload)) {
            try {
               Set<BlockPos> generated = buildingPreviewBlocks(snapshot, key.points(), polygonHeightConfirmed, modes);
               cachedBuildingPreviewBlocks = completedBuildingPreview(
                  snapshot, key.points(), polygonHeightConfirmed, modes, generated
               );
            } catch (RuntimeException exception) {
               failureLogger.accept("Unable to generate FastFormer building preview; using outline fallback", exception);
               cachedBuildingPreviewBlocks = buildingPreviewLimitFallbackSafely(
                  snapshot, key.points(), polygonHeightConfirmed, modes
               );
            }
            cachedBuildingPreviewResultVersion++;
            generation.cancel();
         } else {
            cachedBuildingPreviewBlocks = Set.of();
            if ((workload == PreviewAsyncPolicy.Workload.PLANE || workload == PreviewAsyncPolicy.Workload.VOLUME)
               && modes.fillMode() != FillMode.OUTLINE) {
               cachedBuildingFallbackBlocks = buildingPreviewLimitFallbackSafely(
                  snapshot, key.points(), polygonHeightConfirmed, modes
               );
               cachedBuildingPreviewBlocks = cachedBuildingFallbackBlocks;
            }
            cachedBuildingPreviewProgress = new ProgressiveBlockGeneration(
               PreviewAsyncPolicy.estimateScanCells(key.points(), workload), false
            );
            ProgressiveBlockGeneration progress = cachedBuildingPreviewProgress;
            BuildingPreviewKey requestKey = key;
            generation.start(requestKey,
               () -> {
                  Set<BlockPos> blocks = buildingPreviewBlocks(
                     snapshot, requestKey.points(), polygonHeightConfirmed, modes, progress
                  );
                  progress.complete();
                  return new BuildingBlockResult(requestKey, blocks);
               }, PREVIEW_GENERATION_EXECUTOR);
         }
      }
      return cachedBuildingPreviewBlocks;
   }



   void applyBuildingPreviewResults() {
      BuildingPreviewKey key = cachedBuildingPreviewKey;
      if (key == null || key.stateVersion() != previewState.buildingVersion()
         || key.modifierHeld() != this.modifierHeld.getAsBoolean()) return;
      BuildingPreviewPayload snapshot = previewState.building();
      if (!snapshot.active()) return;
      boolean polygonHeightConfirmed = key.heightConfirmed();
      if (generation.completed()) {
         try {
            Optional<BuildingBlockResult> completed = generation.takeCompleted();
            if (completed.isPresent() && completed.orElseThrow().key().equals(cachedBuildingPreviewKey)) {
               BuildingBlockResult result = completed.orElseThrow();
               FastPlaceGeometry.Modes modes = effectiveBuildingModes(snapshot);
               cachedBuildingPreviewBlocks = completedBuildingPreview(
                  snapshot, result.key().points(), polygonHeightConfirmed, modes, result.blocks()
               );
               cachedBuildingPreviewAtLimit = false;
               cachedBuildingPreviewResultVersion++;
            }
         } catch (CancellationException ignored) {
            // A newer snapped candidate superseded this generation.
         } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cachedBuildingPreviewBlocks = buildingPreviewLimitFallbackSafely(
               snapshot, key.points(), polygonHeightConfirmed, effectiveBuildingModes(snapshot)
            );
            cachedBuildingPreviewAtLimit = false;
            cachedBuildingPreviewResultVersion++;
         } catch (java.util.concurrent.ExecutionException exception) {
            if (!(exception.getCause() instanceof CancellationException)) {
               failureLogger.accept(
                  "Unable to generate FastFormer building preview asynchronously; using outline fallback",
                  exception.getCause()
               );
               cachedBuildingPreviewBlocks = buildingPreviewLimitFallbackSafely(
                  snapshot, key.points(), polygonHeightConfirmed, effectiveBuildingModes(snapshot)
               );
               cachedBuildingPreviewAtLimit = false;
               cachedBuildingPreviewResultVersion++;
            }
         } finally {
            cachedBuildingPreviewProgress = null;
         }
      } else if (cachedBuildingPreviewProgress != null) {
         ProgressiveBlockGeneration.Snapshot progress = cachedBuildingPreviewProgress.snapshot();
         if (cachedBuildingPreviewAtLimit || progress.generated() >= FastPlaceGeometry.PREVIEW_MAX_BLOCKS) {
            holdBuildingPreviewAtFallback(snapshot, key.points(), polygonHeightConfirmed);
         }
      }
   }

   private Set<BlockPos> completedBuildingPreview(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes,
      Set<BlockPos> generated
   ) {
      return GenerationLimitExceeded.is(generated)
         ? buildingPreviewLimitFallbackSafely(snapshot, points, polygonHeightConfirmed, modes)
         : generated;
   }

   private void holdBuildingPreviewAtFallback(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed
   ) {
      cachedBuildingPreviewAtLimit = true;
      if (cachedBuildingPreviewProgress != null) {
         cachedBuildingPreviewProgress.cancel();
         cachedBuildingPreviewProgress = null;
      }
      generation.cancel();
      Set<BlockPos> fallback = cachedBuildingFallbackBlocks;
      if (fallback.isEmpty()) {
         fallback = buildingPreviewLimitFallbackSafely(
            snapshot, points, polygonHeightConfirmed, effectiveBuildingModes(snapshot)
         );
         cachedBuildingFallbackBlocks = fallback;
      }
      if (!cachedBuildingPreviewBlocks.equals(fallback)) {
         cachedBuildingPreviewBlocks = fallback;
         cachedBuildingPreviewResultVersion++;
      }
   }

   private Set<BlockPos> buildingPreviewLimitFallback(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes
   ) {
      if (modes.fillMode() != FillMode.OUTLINE) {
         Set<BlockPos> outline = buildingPreviewBlocks(
            snapshot,
            points,
            polygonHeightConfirmed,
            modes.withFillMode(FillMode.OUTLINE),
            null,
            PreviewAsyncPolicy.OUTLINE_BLOCK_LIMIT
         );
         if (!GenerationLimitExceeded.is(outline)) {
            return outline;
         }
      }
      return Set.copyOf(new HashSet<>(points));
   }

   private Set<BlockPos> buildingPreviewLimitFallbackSafely(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes
   ) {
      try {
         return buildingPreviewLimitFallback(snapshot, points, polygonHeightConfirmed, modes);
      } catch (RuntimeException exception) {
         failureLogger.accept("Unable to generate FastFormer outline preview; using control points", exception);
         return Set.copyOf(new HashSet<>(points));
      }
   }

   PreviewAsyncPolicy.Workload buildingPreviewWorkload(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed
   ) {
      if (points.size() <= 2) {
         return PreviewAsyncPolicy.Workload.LINE;
      }
      if (snapshot.faceMode() == FaceMode.POLYGON && !snapshot.polygonClosed()) {
         return PreviewAsyncPolicy.Workload.PATH;
      }
      if (points.size() == 3 || snapshot.faceMode() == FaceMode.POLYGON && !polygonHeightConfirmed) {
         return PreviewAsyncPolicy.Workload.PLANE;
      }
      return PreviewAsyncPolicy.Workload.VOLUME;
   }

   private void cancelBuildingPreviewGeneration() {
      generation.cancel();
      if (cachedBuildingPreviewProgress != null) {
         cachedBuildingPreviewProgress.cancel();
         cachedBuildingPreviewProgress = null;
      }
      cachedBuildingPreviewAtLimit = false;
      cachedBuildingFallbackBlocks = Set.of();
   }

   BuildingRenderFrame buildingRenderFrame(
      BuildingPreviewPayload snapshot,
      ResolvedPlacementEffect previewEffect,
      Set<BlockPos> previewBlocks,
      Set<BlockPos> candidateBlocks,
      BlockPos candidate,
      BlockPos hoveredPoint
   ) {
      BuildingRenderKey key = new BuildingRenderKey(
         previewState.buildingVersion(),
         cachedBuildingPreviewKey,
         cachedBuildingPreviewResultVersion,
         candidate,
         hoveredPoint
      );
      if (!key.equals(cachedBuildingRenderKey)) {
         GeometryPreviewBlocks.Layers split = GeometryPreviewBlocks.layersPreservingConfirmed(
            PlacementEffectPreview.applyToTargets(previewEffect, confirmedBuildingBlocks(snapshot)), previewBlocks
         );
         HashSet<BlockPos> pending = new HashSet<>(split.pending());
         pending.addAll(candidateBlocks);
         Set<BlockPos> all = PreviewGeometrySupport.unionBlocks(split.confirmed(), pending);
         BuildingRenderLayers layers = new BuildingRenderLayers(
            // Keep point cells in the shell. Removing them makes a one-block
            // first-point preview render only its control-point/outline lines.
            split.confirmed(),
            pending,
            all
         );
         List<ControlPoint> controlPoints = ControlPointPresentation.building(snapshot, candidate, hoveredPoint);
         Map<BlockPos, BuildingSpecialBlock> specialBlockStyles = buildingSpecialBlockStyles(
            controlPoints, layers.allBlocks()
         );
         HashSet<BlockPos> confirmedMarkers = new HashSet<>();
         specialBlockStyles.forEach((pos, special) -> {
            if (special.confirmed()) {
               confirmedMarkers.add(pos);
            }
         });
         HashSet<BlockPos> pendingMarkers = new HashSet<>();
         for (ControlPoint point : controlPoints) {
            if (!point.confirmed()) {
               pendingMarkers.add(BlockPos.containing(point.center()));
            }
         }
         cachedBuildingRenderKey = key;
         cachedBuildingRenderFrame = new BuildingRenderFrame(
            layers,
            BUILDING_SHELL_BLOCKS_CACHE.resolve(layers, confirmedMarkers, pendingMarkers),
            controlPoints,
            specialBlockStyles,
            previewEffectStates(previewEffect, layers.allBlocks())
         );
      }
      return cachedBuildingRenderFrame;
   }

   private Map<BlockPos, BlockState> previewEffectStates(
      ResolvedPlacementEffect effect,
      Set<BlockPos> previewBlocks
   ) {
      if (effect == null || previewBlocks.isEmpty()) {
         return Map.of();
      }
      return PlacementEffectPreview.resolveStates(effect, previewBlocks);
   }

   FastPlaceGeometry.Modes effectiveBuildingModes(BuildingPreviewPayload snapshot) {
      boolean modifier = modifierHeld.getAsBoolean();
      FastPlaceGeometry.Modes modes = snapshot.modes().withModifierHeld(modifier);
      QuickShapeStage stage = QuickShapeStage.resolve(snapshot.points().size(), snapshot.faceMode(), snapshot.polygonClosed());
      if (snapshot.faceMode() != FaceMode.POLYGON
         && modifier
         && (stage == QuickShapeStage.FACE || stage == QuickShapeStage.VOLUME)) {
         modes = modes.withFaceTieBias(LineTieBias.OPPOSITE);
      }
      boolean firstRaycastPoint = stage == QuickShapeStage.POINT
         && snapshot.pointMode() == PointMode.RAYCAST
         && snapshot.points().isEmpty();
      boolean raycastLine = stage == QuickShapeStage.LINE && snapshot.lineMode() == LineMode.RAYCAST;
      if (firstRaycastPoint || raycastLine) {
         return modes.withRaycastPlacement(
            modifier ? RaycastPlacement.EMBEDDED : RaycastPlacement.SURFACE
         );
      }
      return modes;
   }

   private Map<BlockPos, BuildingSpecialBlock> buildingSpecialBlockStyles(
      List<ControlPoint> controlPoints, Set<BlockPos> ghostBlocks
   ) {
      HashMap<BlockPos, BuildingSpecialBlock> result = new HashMap<>();
      for (ControlPoint point : controlPoints) {
         BlockPos pos = BlockPos.containing(point.center());
         if (point.confirmed() && ghostBlocks.contains(pos)) {
            result.put(
               pos.immutable(),
               new BuildingSpecialBlock(
                  point.feedback().style(point.role(), point.hovered()), point.confirmed()
               )
            );
         }
      }
      return Map.copyOf(result);
   }

}
