package io.github.fastformer.client.preview.geometry;

import io.github.fastformer.client.render.geometry.PreviewGeometrySupport;
import io.github.fastformer.client.render.model.GeometryRenderLayers;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.cone.ConePrismStage;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPoint;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGenerator;
import io.github.fastformer.fastplace.geometry.generation.ConePrismParameters;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Owns only the drawing layers derived from a shared geometry plan. */
final class GeometryPreviewModels {
   private GeometryPreviewPayload snapshot;
   private GeometryPreviewPlan plan;
   private boolean modifierHeld;
   private GeometryPreviewModel model;

   GeometryPreviewModel build(GeometryPreviewInput input) {
      if (plan != input.plan() || snapshot != input.snapshot() || modifierHeld != input.modifierHeld()) {
         plan = input.plan();
         snapshot = input.snapshot();
         modifierHeld = input.modifierHeld();
         model = new GeometryPreviewModel(plan, layers(plan, input));
      }
      return model;
   }

   void clear() { plan = null; snapshot = null; model = null; }

   private static GeometryRenderLayers layers(GeometryPreviewPlan plan, GeometryPreviewInput input) {
      Set<BlockPos> markers = plan.controlPoints().stream().map(ControlPoint::pos).collect(java.util.stream.Collectors.toUnmodifiableSet());
      Set<BlockPos> pending = plan.pendingBlocks();
      var snapshot = input.snapshot();
      if (pending.isEmpty() && snapshot.mode() == GeometryMode.CONE_PRISM
         && snapshot.conePlaneMode().stageFor(snapshot.points().size()) == ConePrismStage.BODY && !input.modifierHeld()) {
         pending = coneBase(snapshot);
      }
      return new GeometryRenderLayers(PreviewGeometrySupport.withoutBlocks(plan.ghostBlocks(), markers),
         PreviewGeometrySupport.withoutBlocks(pending, markers));
   }

   private static Set<BlockPos> coneBase(GeometryPreviewPayload snapshot) {
      int count = Math.min(snapshot.pointLocations().size(), snapshot.conePlaneMode().facePointCount());
      if (count < snapshot.conePlaneMode().facePointCount()) return Set.of();
      List<Vec3> points = List.copyOf(snapshot.pointLocations().subList(0, count));
      return ConePrismGenerator.baseOutline(new ConePrismParameters(points, Optional.empty(), snapshot.coneShapeVariant(),
         snapshot.conePlaneMode(), snapshot.coneRadius(), snapshot.coneScaleX(), snapshot.coneScaleZ(), snapshot.coneTopScaleOffset(),
         snapshot.coneTopOffset(), snapshot.coneRotationRadians()), 16_384);
   }
}
