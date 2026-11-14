package io.github.fastformer.common.geometry.workflow.plan;

import io.github.fastformer.common.geometry.workflow.ConvexWorkflowView;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.raster.GeometryBuildResult;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.preview.GeometryPreviewBlocks;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.GeometryStage;
import io.github.fastformer.common.geometry.model.TransformFrame;
import io.github.fastformer.common.geometry.controlpoint.ControlPoint;
import io.github.fastformer.common.geometry.ControlPointRole;
import io.github.fastformer.common.geometry.generation.volume.ArbitraryConvexPolyhedronGenerator;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.common.geometry.interaction.PointerGesture;
import io.github.fastformer.common.geometry.preview.GeometryStageDisplay;
import io.github.fastformer.common.geometry.gizmo.GizmoTextComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public final class ConvexPolyhedronWorkflow extends ShapeWorkflow<ConvexWorkflowView> {
   public ConvexPolyhedronWorkflow() { super(ConvexWorkflowView.class); }

   public static final int MAX_CONTROL_POINTS = 64;
   private static final int PREVIEW_EDGE_LIMIT = 4096;
   private static final int DETAILED_PREVIEW_BLOCK_LIMIT = 16000;

   @Override
   public GeometryMode mode() {
      return GeometryMode.CONVEX_POLYHEDRON;
   }

   @Override
   protected GeometryStage stageView(ConvexWorkflowView view) {
      boolean ready = ArbitraryConvexPolyhedronGenerator.ready(view.pointLocations());
      GeometryStage stage = GeometryStage.collecting("convex_polyhedron.points")
         .withAction(GeometryAction.POINT_INPUT, view.pointCount() < MAX_CONTROL_POINTS)
         .allow(GeometryAction.SUBMODE)
         .withAction(GeometryAction.CONFIRM, ready);
      return view.selectedPointIndex() >= 0 ? stage.allow(GeometryAction.GIZMO_DRAG) : stage;
   }

   @Override
   protected boolean canFillView(ConvexWorkflowView view) {
      return ArbitraryConvexPolyhedronGenerator.ready(view.pointLocations());
   }

   @Override
   protected GeometryBuildResult buildView(ConvexWorkflowView view, FillMode fillMode, int maxBlocks) {
      List<Vec3> points = List.copyOf(view.pointLocations());
      return GeometryBuildResult.ready(
         ArbitraryConvexPolyhedronGenerator.estimateScanCells(points),
         maxBlocks,
         () -> ArbitraryConvexPolyhedronGenerator.generateResult(points, fillMode, maxBlocks)
      );
   }

   @Override
   protected Component blockedMessageView(ConvexWorkflowView view) {
      return Component.translatable("fastformer.geometry.message.fill_blocked_convex_polyhedron");
   }

   @Override
   protected Component pointBlockedMessageView(ConvexWorkflowView view) {
      return Component.translatable("fastformer.geometry.message.convex_polyhedron_point_limit", MAX_CONTROL_POINTS);
   }

   @Override
   protected GeometryPreviewPlan previewView(
      ConvexWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, GeometryHit candidateHit, Vec3 eye
   ) {
      ArrayList<Vec3> previewPoints = new ArrayList<>(view.pointLocations());
      Vec3 candidate = null;
      if (candidateHit != null && previewPoints.size() < MAX_CONTROL_POINTS) {
         Vec3 snapped = candidateHit.halfGridPoint(view.modifierHeld());
         if (previewPoints.stream().noneMatch(point -> point.distanceToSqr(snapped) < 1.0E-12)) {
            previewPoints.add(snapped);
            candidate = snapped;
         }
      }

      long scanCells = ArbitraryConvexPolyhedronGenerator.estimateScanCells(previewPoints);
      boolean detailed = scanCells <= DETAILED_PREVIEW_BLOCK_LIMIT;
      Set<BlockPos> outline = detailed
         ? ArbitraryConvexPolyhedronGenerator.generate(previewPoints, view.fillMode(), DETAILED_PREVIEW_BLOCK_LIMIT)
         : ArbitraryConvexPolyhedronGenerator.previewOutline(previewPoints, PREVIEW_EDGE_LIMIT);
      AxisGizmo gizmo = selectedGizmo(view);
      return GeometryPreviewPlan.builder(points, hoveredPoint)
         .stage(Component.translatable(this.stage(view).labelKey()))
         .hud(
            Component.translatable("fastformer.geometry.mode.convex_polyhedron"),
            Component.translatable("fastformer.message.convex_polyhedron_hint")
         )
         .stageDisplay(GeometryStageDisplay.value(
            Component.translatable("fastformer.geometry.convex_polyhedron.point_count", view.pointCount())
         ))
         .gizmo(gizmo)
         .blocks(GeometryPreviewBlocks.layers(Set.of(), outline))
         .placementReady(
            ArbitraryConvexPolyhedronGenerator.ready(view.pointLocations())
               && ArbitraryConvexPolyhedronGenerator.estimateScanCells(view.pointLocations()) <= DETAILED_PREVIEW_BLOCK_LIMIT
         )
         .controlPoints(controlPoints(view.pointLocations(), view.pointRoles(), candidate, hoveredPoint))
          .interactionTargets(this.interactionTargets(view))
          .build();
   }

   @Override
   protected List<GeometryInteractionTarget> interactionTargetsView(ConvexWorkflowView view) {
      ArrayList<GeometryInteractionTarget> result = new ArrayList<>(view.pointLocations().size());
      for (int index = 0; index < view.pointLocations().size(); index++) {
         result.add(GeometryInteractionTarget.controlPoint(index, view.pointLocations().get(index)));
      }
      return List.copyOf(result);
   }

   private static AxisGizmo selectedGizmo(ConvexWorkflowView view) {
      int index = view.selectedPointIndex();
      if (index < 0 || index >= view.pointLocations().size()) {
         return null;
      }
      Vec3 center = view.pointLocations().get(index);
      double axisLength = Math.max(
         2.0,
         view.pointLocations().stream().mapToDouble(point -> point.distanceTo(center)).max().orElse(0.0)
      );
       return AxisGizmo.inFrame(
            TransformFrame.world(center),
            axisLength,
            Math.max(0.25, axisLength * 0.10),
            AxisGizmo.Operation.MOVE
         )
         .withPointGizmoHover()
         .withTextComponent(GizmoTextComponent.pointLevel());
   }

   private static List<ControlPoint> controlPoints(
      List<Vec3> confirmed, List<ControlPointRole> roles, Vec3 candidate, BlockPos hoveredPoint
   ) {
      ArrayList<ControlPoint> result = new ArrayList<>(confirmed.size() + (candidate == null ? 0 : 1));
      for (int index = 0; index < confirmed.size(); index++) {
         Vec3 point = confirmed.get(index);
         ControlPointRole role = index < roles.size()
            ? roles.get(index)
            : index == 0 ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY;
         result.add(ControlPoint.precise(point, role));
      }
      if (candidate != null) {
         result.add(ControlPoint.pending(candidate, ControlPointRole.SECONDARY));
      }
      return List.copyOf(result);
   }
}
