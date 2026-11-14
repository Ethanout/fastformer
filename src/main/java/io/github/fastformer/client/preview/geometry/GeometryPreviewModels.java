package io.github.fastformer.client.preview.geometry;

import io.github.fastformer.client.render.geometry.PreviewGeometrySupport;
import io.github.fastformer.client.render.model.GeometryPlanKey;
import io.github.fastformer.client.render.model.GeometryRenderLayers;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.cone.ConePrismStage;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPoint;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGenerator;
import io.github.fastformer.fastplace.geometry.generation.ConePrismParameters;
import io.github.fastformer.fastplace.workflow.GeometryWorkflowView;
import io.github.fastformer.fastplace.workflow.GeometryWorkflows;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Owns geometry plan identity and the block layers derived from that plan. */
final class GeometryPreviewModels {
   private final BiConsumer<String, Throwable> failureLogger;
   private final Function<GeometryPreviewInput, GeometryPreviewPlan> planner;
   private GeometryPlanKey key;
   private GeometryPreviewModel model;

   GeometryPreviewModels(BiConsumer<String, Throwable> failureLogger) {
      this(failureLogger, GeometryPreviewModels::plan);
   }

   GeometryPreviewModels(BiConsumer<String, Throwable> failureLogger, Function<GeometryPreviewInput, GeometryPreviewPlan> planner) {
      this.failureLogger = Objects.requireNonNull(failureLogger, "failureLogger");
      this.planner = Objects.requireNonNull(planner, "planner");
   }

   GeometryPreviewModel build(GeometryPreviewInput input) {
      var next = new GeometryPlanKey(input.version(), input.modifierHeld(), input.eye(), input.view(), input.candidate(), input.hoveredPoint());
      if (!next.equals(key)) {
         GeometryPreviewPlan plan;
         try {
            plan = Objects.requireNonNull(planner.apply(input), "geometry plan");
         } catch (RuntimeException exception) {
            failureLogger.accept("Unable to generate FastFormer geometry preview plan; using control points", exception);
            plan = GeometryPreviewPlan.controlPoints(input.snapshot().points(), input.hoveredPoint());
         }
         var layers = layers(plan, input);
         model = new GeometryPreviewModel(plan, layers);
         key = next;
      }
      return model;
   }

   void clear() { key = null; model = null; }

   static GeometryWorkflowView workflow(GeometryPreviewPayload snapshot, boolean modifierHeld, Vec3 view) {
      return new GeometryWorkflowView(snapshot.mode(), snapshot.points().size(), snapshot.pointLocations(), snapshot.pointRoles(),
         snapshot.closed(), modifierHeld, snapshot.polyhedronShapeVariant(), snapshot.coneShapeVariant(), snapshot.compoundShapeVariant(),
         snapshot.polyhedronSizeMode(), snapshot.extrusion(), snapshot.conePlaneMode(), snapshot.coneRadius(), snapshot.coneScaleX(),
         snapshot.coneScaleZ(), snapshot.coneTopScaleOffset(), snapshot.coneTopOffset(), snapshot.coneRotationRadians(), snapshot.coneGizmoLocal(),
         snapshot.rotation(), snapshot.polyhedronLocalScale(), snapshot.polyhedronWorldScale(), snapshot.polyhedronGizmoLocal(),
         snapshot.fillMode(), view, snapshot.selectedPointIndex());
   }

   private static GeometryPreviewPlan plan(GeometryPreviewInput input) {
      return GeometryWorkflows.previewPlan(workflow(input.snapshot(), input.modifierHeld(), input.view()),
         input.snapshot().points(), input.hoveredPoint(), input.candidate(), input.eye());
   }

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
