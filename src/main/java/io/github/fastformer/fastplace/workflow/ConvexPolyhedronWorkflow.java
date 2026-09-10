package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.fastplace.*;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.geometry.ControlPoint;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryBuildResult;
import io.github.fastformer.fastplace.geometry.GeometryPreviewBlocks;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryStage;
import io.github.fastformer.fastplace.geometry.GeometryStageDisplay;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GizmoTextComponent;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import io.github.fastformer.fastplace.geometry.generation.ArbitraryConvexPolyhedronGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public final class ConvexPolyhedronWorkflow implements GeometryWorkflow {
   private static final int MAX_CONTROL_POINTS = 64;
   private static final int PREVIEW_EDGE_LIMIT = 4096;
   private static final int DETAILED_PREVIEW_BLOCK_LIMIT = 16000;

   @Override
   public GeometryMode mode() {
      return GeometryMode.CONVEX_POLYHEDRON;
   }

   @Override
   public void onAddPoint(GeometrySession session, GeometryActionContext context, GeometryHit hit) {
      if (session.pointLocations().size() >= MAX_CONTROL_POINTS) {
         return;
      }
      session.clearSelectedControlPoint();
      Vec3 candidate = hit.halfGridPoint(context.modifierHeld());
      if (session.pointLocations().stream().noneMatch(point -> point.distanceToSqr(candidate) < 1.0E-12)) {
         session.addPoint(candidate);
      }
   }

   @Override
   public GeometryStage stage(GeometryWorkflowView view) {
      boolean ready = ArbitraryConvexPolyhedronGenerator.ready(view.pointLocations());
      GeometryStage stage = GeometryStage.collecting("convex_polyhedron.points")
         .withAction(GeometryAction.POINT_INPUT, view.pointCount() < MAX_CONTROL_POINTS)
         .allow(GeometryAction.SUBMODE)
         .withAction(GeometryAction.CONFIRM, ready);
      return view.selectedPointIndex() >= 0 ? stage.allow(GeometryAction.GIZMO_DRAG) : stage;
   }

   @Override
   public boolean onGizmoDrag(
      GeometrySession session,
      GeometryActionContext context,
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      int steps
   ) {
      if (session.selectedControlPoint() < 0 || steps == 0 || operation != AxisGizmo.Operation.MOVE) {
         return false;
      }
      Vec3 direction = switch (axis) {
         case X -> new Vec3(1.0, 0.0, 0.0);
         case Y -> new Vec3(0.0, 1.0, 0.0);
         case Z -> new Vec3(0.0, 0.0, 1.0);
      };
      session.moveSelectedPoint(direction.scale((double)steps * 0.5));
      return true;
   }

   @Override
   public boolean onInteraction(
      GeometrySession session,
      GeometryActionContext context,
      GeometryInteractionTarget.TargetType targetType,
      int index,
      GeometryInteractionAction action,
      PointerGesture gesture
   ) {
      if (targetType != GeometryInteractionTarget.TargetType.CONTROL_POINT
         || action != GeometryInteractionAction.SELECT_CONTROL_POINT
         || (gesture != PointerGesture.LEFT_CLICK && gesture != PointerGesture.RIGHT_CLICK)
         || index < 0
         || index >= session.pointLocations().size()) {
         return false;
      }
      session.selectControlPoint(index);
      return true;
   }

   @Override
   public boolean canFill(GeometrySession session) {
      return ArbitraryConvexPolyhedronGenerator.ready(session.pointLocations());
   }

   @Override
   public GeometryBuildResult build(GeometrySession session, GeometryActionContext context, FillMode fillMode, int maxBlocks) {
      List<Vec3> points = List.copyOf(session.pointLocations());
      return GeometryBuildResult.ready(
         ArbitraryConvexPolyhedronGenerator.estimateScanCells(points),
         maxBlocks,
         () -> ArbitraryConvexPolyhedronGenerator.generateResult(points, fillMode, maxBlocks)
      );
   }

   @Override
   public Component blockedMessage(GeometrySession session) {
      return Component.translatable("fastformer.geometry.message.fill_blocked_convex_polyhedron");
   }

   @Override
   public Component pointBlockedMessage(GeometrySession session) {
      return Component.translatable("fastformer.geometry.message.convex_polyhedron_point_limit", MAX_CONTROL_POINTS);
   }

   @Override
   public GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, GeometryHit candidateHit, Vec3 eye
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
   public List<GeometryInteractionTarget> interactionTargets(GeometryWorkflowView view) {
      ArrayList<GeometryInteractionTarget> result = new ArrayList<>(view.pointLocations().size());
      for (int index = 0; index < view.pointLocations().size(); index++) {
         result.add(GeometryInteractionTarget.controlPoint(index, view.pointLocations().get(index)));
      }
      return List.copyOf(result);
   }

   private static AxisGizmo selectedGizmo(GeometryWorkflowView view) {
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
