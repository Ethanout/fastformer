package io.github.fastformer.common.geometry.workflow.plan;

import io.github.fastformer.common.geometry.workflow.PolyhedronWorkflowView;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.raster.GeometryBuildResult;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.GeometryPoints;
import io.github.fastformer.common.geometry.preview.GeometryPreviewBlocks;
import io.github.fastformer.common.geometry.preview.GeometryPreviewGuides;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.GeometryStage;
import io.github.fastformer.common.geometry.generation.shape.SphereGenerator;
import io.github.fastformer.common.geometry.generation.shape.PolyhedronParameters;
import io.github.fastformer.common.geometry.PolyhedronSizeMode;
import io.github.fastformer.common.geometry.preview.GeometryStageDisplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;

public final class PolyhedronWorkflow extends ShapeWorkflow<PolyhedronWorkflowView> {
   public PolyhedronWorkflow() { super(PolyhedronWorkflowView.class); }

   private static final int DETAILED_PREVIEW_SCAN_LIMIT = 16000;
   private static final int FALLBACK_PREVIEW_BLOCK_LIMIT = 4096;

   @Override
   public GeometryMode mode() {
      return GeometryMode.POLYHEDRON;
   }

   @Override
   protected GeometryStage stageView(PolyhedronWorkflowView view) {
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
   protected boolean canFillView(PolyhedronWorkflowView view) {
      return view.closed() && GeometryPoints.polyhedron(
         view.pointLocations(), view.pointRoles(), view.polyhedronSizeMode()).canGenerate();
   }

   @Override
   protected GeometryBuildResult buildView(PolyhedronWorkflowView view, FillMode fillMode, int maxBlocks) {
      PolyhedronParameters parameters = parameters(view);
      return GeometryBuildResult.ready(
         SphereGenerator.estimateScanCells(parameters),
         maxBlocks,
         () -> SphereGenerator.generateResult(parameters, fillMode, maxBlocks)
      );
   }

   @Override
   protected Component blockedMessageView(PolyhedronWorkflowView view) {
      return Component.translatable("fastformer.geometry.message.fill_blocked_polyhedron");
   }

   @Override
   protected GeometryPreviewPlan previewView(
      PolyhedronWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, BlockPos candidatePoint, Vec3 eye
   ) {
      return this.previewPlan(view, points, hoveredPoint, candidatePoint == null ? null : GeometryHit.point(candidatePoint), eye);
   }

   @Override
   protected GeometryPreviewPlan previewView(
      PolyhedronWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, GeometryHit candidateHit, Vec3 eye
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
         SphereGenerator.estimateScanCells(confirmedParameters),
         DETAILED_PREVIEW_SCAN_LIMIT,
         () -> SphereGenerator.generate(confirmedParameters, view.fillMode(), DETAILED_PREVIEW_SCAN_LIMIT),
         () -> SphereGenerator.previewOutline(confirmedParameters, FALLBACK_PREVIEW_BLOCK_LIMIT)
      );
      Set<BlockPos> previewGhost = GeometryPreviewBlocks.generatedOrFallback(
         SphereGenerator.estimateScanCells(previewParameters),
         DETAILED_PREVIEW_SCAN_LIMIT,
         () -> SphereGenerator.generate(previewParameters, view.fillMode(), DETAILED_PREVIEW_SCAN_LIMIT),
         () -> SphereGenerator.previewOutline(previewParameters, FALLBACK_PREVIEW_BLOCK_LIMIT)
      );
      return GeometryPreviewPlan.builder(points, hoveredPoint)
         .stage(Component.translatable(this.stage(view).labelKey()))
         .hud(Component.empty(), hint)
         .stageDisplay(this.stageDisplay(view))
         .gizmo(gizmo)
         .blocks(GeometryPreviewBlocks.layers(confirmedGhost, previewGhost))
         .placementReady(
            view.closed()
               && confirmedParameters.ready()
               && SphereGenerator.estimateScanCells(confirmedParameters) <= DETAILED_PREVIEW_SCAN_LIMIT
         )
         .controlPoints(GeometryPreviewGuides.polyhedronPoints(
            previewLocations, view.pointRoles(), view.polyhedronSizeMode(), view.pointCount()
         ))
         .build();
   }

   private GeometryStageDisplay stageDisplay(PolyhedronWorkflowView view) {
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

   private static PolyhedronParameters parameters(PolyhedronWorkflowView view) {
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
