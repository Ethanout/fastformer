package io.github.fastformer.client.preview.quickshape;

import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;

import io.github.fastformer.client.placement.effect.PlacementEffectPreview;
import io.github.fastformer.client.controlpoint.ControlPointPresentation;
import io.github.fastformer.client.preview.frame.PreviewMode;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.client.render.geometry.PreviewGeometrySupport;
import io.github.fastformer.client.render.guide.GuideRenderer;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import io.github.fastformer.client.session.preview.ClientPreviewState;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.common.geometry.model.GuideLine;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/** Owns quick-shape generation, its per-frame model, shell resources, and cancellation. */
public final class QuickShapePreviewMode implements PreviewMode<QuickShapePreviewInput, QuickShapePreviewModel> {
   private final BuildingShellRenderer shells = new BuildingShellRenderer();
   private final BuildingPreviewCache cache;

   public QuickShapePreviewMode(ClientPreviewState state, BooleanSupplier modifierHeld,
      BiConsumer<String, Throwable> failureLogger, Executor executor) {
      cache = new BuildingPreviewCache(state, modifierHeld, failureLogger, executor, shells::clear);
   }

   public void tick() { cache.applyBuildingPreviewResults(); }
   public FastPlaceGeometry.Modes effectiveModes(BuildingPreviewSnapshot snapshot) { return cache.effectiveBuildingModes(snapshot); }
   @Override public void clear() { cache.resetBuildingPreviewCaches(); }

   @Override public QuickShapePreviewModel buildModel(QuickShapePreviewInput input) {
      var snapshot = input.snapshot();
      var candidate = input.candidate();
      var hovered = input.hoveredPoint();
      var previewPoints = new ArrayList<>(snapshot.points());
      boolean closing = ControlPointPresentation.closingCandidate(snapshot, hovered);
      if (!snapshot.polygonHeightConfirmed() && (candidate != null || closing)) {
         var point = closing ? snapshot.points().getFirst() : candidate;
         if (previewPoints.isEmpty() || !point.equals(previewPoints.getLast())) previewPoints.add(point);
      }
      boolean heightConfirmed = snapshot.polygonHeightConfirmed()
         || snapshot.polygonClosed() && previewPoints.size() > snapshot.points().size();
      var modes = cache.effectiveBuildingModes(snapshot);
      var effect = PlacementEffectPreview.resolve(snapshot, input.player(), input.prototype(), previewPoints,
         heightConfirmed, modes, input.placementContext());
      var previewBlocks = PlacementEffectPreview.applyToTargets(effect,
         cache.buildingPreviewBlocksCached(snapshot, previewPoints, heightConfirmed));
      var candidates = ControlPointPresentation.candidateBlocks(snapshot, candidate, hovered);
      var blocks = cache.buildingRenderFrame(snapshot, effect, previewBlocks, candidates, candidate, hovered);
      var layers = blocks.layers();
      var guides = FastPlaceGeometry.guidePlanes(snapshot.points(), snapshot.polygonClosed(), candidate, layers.allBlocks(),
         snapshot.faceBaseOffset(), snapshot.volumeBaseOffset(), snapshot.perpendicularAnchor(), input.eye(), input.view(), modes);
      boolean confirmedLight = PreviewAsyncPolicy.useOutlineOnly(snapshot.points(),
         cache.buildingPreviewWorkload(snapshot, snapshot.points(), snapshot.polygonHeightConfirmed()))
         || PreviewAsyncPolicy.useLightweightShell(layers.confirmedRenderBlocks().size());
      boolean pendingLight = PreviewAsyncPolicy.useOutlineOnly(previewPoints,
         cache.buildingPreviewWorkload(snapshot, previewPoints, heightConfirmed))
         || PreviewAsyncPolicy.useLightweightShell(layers.pendingRenderBlocks().size());
      var confirmedEdges = PreviewGeometrySupport.outlineGeometryEdges(snapshot.points(), modes.faceMode(),
         snapshot.polygonClosed(), snapshot.polygonHeightConfirmed(), snapshot.polygonVolumeShape(), modes.volumeMode());
      var pendingEdges = PreviewGeometrySupport.outlineGeometryEdges(previewPoints, modes.faceMode(),
         snapshot.polygonClosed(), heightConfirmed, snapshot.polygonVolumeShape(), modes.volumeMode());
      if (pendingEdges.equals(confirmedEdges)) pendingEdges = List.of();
      return new QuickShapePreviewModel(input.player(), input.prototype(), blocks, guides,
         boundaryFallback(confirmedEdges, confirmedLight), boundaryFallback(pendingEdges, pendingLight),
         confirmedLight, pendingLight);
   }

   @Override public void drawWorld(WorldPreviewFrame frame, QuickShapePreviewModel model) {
      var pose = frame.pose();
      var buffers = frame.buffers();
      var camera = frame.camera();
      var blocks = model.blocks();
      var shellBlocks = blocks.shellLayers();
      shells.render(model.player(), pose, buffers, camera, model.prototype(), blocks.stateOverrides(),
         shellBlocks.confirmedRenderBlocks(), shellBlocks.pendingRenderBlocks(), blocks.specialBlockStyles(),
         frame.opacity(), frame.animation().breathPulse(2_400_000_000L), model.confirmedLightweight(), model.pendingLightweight());
      if (!model.confirmedBoundary().isEmpty() || !model.pendingBoundary().isEmpty()) {
         ShapeShellRenderer.renderOutlineEdges(pose, buffers.getBuffer(PENDING_LINES), camera, model.confirmedBoundary(), List.of());
         buffers.endBatch(PENDING_LINES);
         ShapeShellRenderer.renderOutlineEdges(pose, buffers.getBuffer(DYNAMIC_LINES), camera, List.of(), model.pendingBoundary());
         buffers.endBatch(DYNAMIC_LINES);
      }
      BuildingPointPreviewRenderer.render(pose, buffers, camera, blocks.controlPoints(), model.prototype(),
         blocks.stateOverrides(), frame.opacity(), frame.animation());
      GuideRenderer.renderBuildingPlanes(pose, buffers.getBuffer(PENDING_LINES), camera, model.guides(), frame.opacity());
      buffers.endBatch(PENDING_LINES);
   }

   /** Boundary edges replace shells only for large layers; a lone center line stays hidden. */
   static List<GuideLine> boundaryFallback(List<GuideLine> edges, boolean lightweight) {
      return lightweight && edges.size() > 1 ? edges : List.of();
   }
}
