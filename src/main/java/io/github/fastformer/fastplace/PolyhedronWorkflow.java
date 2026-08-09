package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryBuildResult;
import io.github.fastformer.fastplace.geometry.GeometryPreviewBlocks;
import io.github.fastformer.fastplace.geometry.GeometryPreviewGuides;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryStage;
import io.github.fastformer.fastplace.geometry.GeometryStageDisplay;
import io.github.fastformer.fastplace.geometry.generation.PolyhedronGenerator;
import io.github.fastformer.fastplace.geometry.generation.PolyhedronParameters;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

final class PolyhedronWorkflow implements GeometryWorkflow {
   private static final int DETAILED_PREVIEW_SCAN_LIMIT = 16000;
   private static final int FALLBACK_PREVIEW_BLOCK_LIMIT = 4096;

   @Override
   public GeometryMode mode() {
      return GeometryMode.POLYHEDRON;
   }

   @Override
   public void onAddPoint(GeometrySession session, GeometryActionContext context, BlockPos point) {
      this.onAddPoint(session, context, GeometryHit.point(point));
   }

   @Override
   public void onAddPoint(GeometrySession session, GeometryActionContext context, GeometryHit hit) {
      if (!session.hasPolyhedronFirstInput()) {
         session.addPoint(hit.spherePoint(context.modifierHeld()));
      } else if (!session.closed()) {
         session.enterPolyhedronAdjust(hit.spherePoint(context.modifierHeld()));
      }
   }

   @Override
   public boolean onScroll(GeometrySession session, GeometryActionContext context, int steps) {
      if (!this.allows(session, GeometryAction.SCALAR_ADJUST)) {
         return false;
      }
      if (!session.closed()) {
         session.adjustPolyhedronRadius(steps);
      }
      return true;
   }

   @Override
   public boolean onCycleMode(GeometrySession session, GeometryActionContext context) {
      if (session.closed()) {
         session.togglePolyhedronGizmoFrame();
         return true;
      }
      if (session.hasPolyhedronCenter()) {
         session.cyclePolyhedronSizeMode();
         return true;
      }
      session.cyclePolyhedronSizeMode();
      return true;
   }

   @Override
   public boolean onGizmoDrag(
      GeometrySession session,
      GeometryActionContext context,
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      int steps
   ) {
      if (!session.closed() || steps == 0 || operation == null || axis == null) {
         return false;
      }
      return switch (operation) {
         case MOVE -> {
            session.movePolyhedron(session.polyhedronGizmoAxis(axis).scale((double)steps * 0.5));
            yield true;
         }
         case SCALE -> {
            session.adjustPolyhedronScale(axis, steps);
            yield true;
         }
         case ROTATE -> false;
      };
   }

   @Override
   public GeometryStage stage(GeometryWorkflowView view) {
      if (view.closed()) {
         return GeometryStage.ready("sphere.adjust")
            .allow(GeometryAction.MODE_CYCLE)
            .allow(GeometryAction.GIZMO_DRAG);
      }
      return view.pointCount() >= 1
          ? GeometryStage.collectingWithScroll("sphere.radius")
            .allow(GeometryAction.MODE_CYCLE)
            .allow(GeometryAction.SUBMODE)
         : GeometryStage.collecting("sphere.center")
            .allow(GeometryAction.MODE_CYCLE)
            .allow(GeometryAction.SUBMODE);
   }

   @Override
   public boolean canFill(GeometrySession session) {
      return session.closed() && session.canGeneratePolyhedron();
   }

   @Override
   public GeometryBuildResult build(GeometrySession session, GeometryActionContext context, FillMode fillMode, int maxBlocks) {
      PolyhedronParameters parameters = parameters(session);
      return GeometryBuildResult.ready(
         PolyhedronGenerator.estimateScanCells(parameters),
         () -> PolyhedronGenerator.generate(parameters, fillMode, maxBlocks)
      );
   }

   @Override
   public Component blockedMessage(GeometrySession session) {
      return Component.translatable("fastformer.geometry.message.fill_blocked_polyhedron");
   }

   @Override
   public GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, BlockPos candidatePoint, Vec3 eye
   ) {
      return this.previewPlan(view, points, hoveredPoint, candidatePoint == null ? null : GeometryHit.point(candidatePoint), eye);
   }

   @Override
   public GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, GeometryHit candidateHit, Vec3 eye
   ) {
      MutableComponent hint = view.closed()
         ? Component.translatable("fastformer.message.polyhedron_hint_confirm")
         : Component.translatable("fastformer.message.polyhedron_hint_start");
      AxisGizmo gizmo = null;
      PolyhedronParameters confirmedParameters = parameters(view);
      if (view.closed() && confirmedParameters.ready()) {
         Vec3 center = confirmedParameters.center();
         double radius = confirmedParameters.radius(2.0);
         double axisLength = Math.max(2.0, radius * confirmedParameters.maxScale() * 1.25);
         gizmo = AxisGizmo.inFrame(
            confirmedParameters.gizmoFrame(),
            axisLength,
            Math.max(0.22, axisLength * 0.05),
            AxisGizmo.Operation.MOVE,
            AxisGizmo.Operation.SCALE
         );
      }
      ArrayList<Vec3> previewLocations = new ArrayList<>(view.pointLocations());
      if (!view.closed() && previewLocations.size() < 2 && candidateHit != null) {
         Vec3 candidate = candidateHit.spherePoint(view.modifierHeld());
         if (previewLocations.size() == 1 && view.polyhedronSizeMode() == PolyhedronSizeMode.RADIUS) {
            candidate = snappedRadiusPoint(previewLocations.getFirst(), candidate);
         }
         if (previewLocations.isEmpty() || !candidate.equals(previewLocations.getLast())) {
            previewLocations.add(candidate);
         }
      }
      GeometryPoints.Polyhedron previewPoints = GeometryPoints.polyhedron(
         previewLocations, view.pointRoles(), view.polyhedronSizeMode()
      );
      PolyhedronParameters previewParameters = new PolyhedronParameters(
         previewPoints.center().orElse(null),
         previewPoints.radiusPoint().orElse(null),
         0,
         view.rotation(),
         view.polyhedronLocalScale(),
         view.polyhedronWorldScale(),
         view.polyhedronGizmoLocal()
      );
      Set<BlockPos> confirmedGhost = GeometryPreviewBlocks.generatedOrFallback(
         PolyhedronGenerator.estimateScanCells(confirmedParameters),
         DETAILED_PREVIEW_SCAN_LIMIT,
         () -> PolyhedronGenerator.generate(confirmedParameters, view.fillMode(), DETAILED_PREVIEW_SCAN_LIMIT),
         () -> PolyhedronGenerator.previewOutline(confirmedParameters, FALLBACK_PREVIEW_BLOCK_LIMIT)
      );
      Set<BlockPos> previewGhost = GeometryPreviewBlocks.generatedOrFallback(
         PolyhedronGenerator.estimateScanCells(previewParameters),
         DETAILED_PREVIEW_SCAN_LIMIT,
         () -> PolyhedronGenerator.generate(previewParameters, view.fillMode(), DETAILED_PREVIEW_SCAN_LIMIT),
         () -> PolyhedronGenerator.previewOutline(previewParameters, FALLBACK_PREVIEW_BLOCK_LIMIT)
      );
      return GeometryPreviewPlan.builder(points, hoveredPoint)
         .stage(Component.translatable(this.stage(view).labelKey()))
         .hud(Component.empty(), hint)
         .stageDisplay(this.stageDisplay(view))
         .gizmo(gizmo)
         .blocks(GeometryPreviewBlocks.layers(confirmedGhost, previewGhost))
         .controlPoints(GeometryPreviewGuides.polyhedronPoints(
            previewLocations, view.pointRoles(), view.polyhedronSizeMode(), view.pointCount()
         ))
         .build();
   }

   private GeometryStageDisplay stageDisplay(GeometryWorkflowView view) {
      if (view.closed()) {
         return GeometryStageDisplay.modes(
            List.of(
               new GeometryStageDisplay.Mode(Component.translatable("fastformer.geometry.gizmo.world"), !view.polyhedronGizmoLocal()),
               new GeometryStageDisplay.Mode(Component.translatable("fastformer.geometry.gizmo.local"), view.polyhedronGizmoLocal())
            )
         );
      }
      return GeometryStageDisplay.modes(
         java.util.Arrays.stream(PolyhedronSizeMode.values())
            .map(mode -> new GeometryStageDisplay.Mode(Component.translatable(mode.translationKey()), mode == view.polyhedronSizeMode()))
            .toList()
      );
   }

   private static Vec3 snappedRadiusPoint(Vec3 center, Vec3 candidate) {
      Vec3 direction = candidate.subtract(center);
      double radius = Math.max(0.5, Math.round(direction.length() * 2.0) * 0.5);
      Vec3 normalized = direction.lengthSqr() < 1.0E-12 ? new Vec3(1.0, 0.0, 0.0) : direction.normalize();
      return center.add(normalized.scale(radius));
   }

   private static PolyhedronParameters parameters(GeometrySession session) {
      GeometryPoints.Polyhedron points = session.polyhedronPoints();
      return new PolyhedronParameters(
         points.center().orElse(null),
         points.radiusPoint().orElse(null),
         0,
         session.rotation(),
         session.polyhedronLocalScale(),
         session.polyhedronWorldScale(),
         session.polyhedronGizmoLocal()
      );
   }

   private static PolyhedronParameters parameters(GeometryWorkflowView view) {
      GeometryPoints.Polyhedron points = GeometryPoints.polyhedron(
         view.pointLocations(), view.pointRoles(), view.polyhedronSizeMode()
      );
      return new PolyhedronParameters(
         points.center().orElse(null),
         points.radiusPoint().orElse(null),
         0,
         view.rotation(),
         view.polyhedronLocalScale(),
         view.polyhedronWorldScale(),
         view.polyhedronGizmoLocal()
      );
   }

}
