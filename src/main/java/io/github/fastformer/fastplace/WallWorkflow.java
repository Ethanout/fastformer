package io.github.fastformer.fastplace;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import io.github.fastformer.fastplace.geometry.ControlPoint;
import io.github.fastformer.fastplace.geometry.ControlPointFeedback;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import io.github.fastformer.fastplace.geometry.GeometryBuildResult;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryConstraints;
import io.github.fastformer.fastplace.geometry.GeometryPreviewBlocks;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryStage;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import io.github.fastformer.fastplace.geometry.generation.WallGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;

final class WallWorkflow implements GeometryWorkflow {
   @Override
   public GeometryMode mode() {
      return GeometryMode.WALL;
   }

   @Override
   public void onAddPoint(GeometrySession session, GeometryActionContext context, BlockPos point) {
      session.addOrClose(this.planePoint(session, context, point));
   }

   @Override
   public void onAddPoint(GeometrySession session, GeometryActionContext context, GeometryHit hit) {
      if (hit == null) {
         return;
      }
      this.onAddPoint(session, context, this.raycastPoint(hit, context.modifierHeld()));
   }

   @Override
   public boolean onScroll(GeometrySession session, GeometryActionContext context, int steps) {
      if (!this.allows(session, GeometryAction.SCALAR_ADJUST)) {
         return false;
      }
      session.adjustExtrusion(OperationGeometry.viewAxisStep(context.view(), steps));
      return true;
   }

   @Override
   public boolean onClosePath(GeometrySession session, GeometryActionContext context) {
      return session.points().size() >= 3 && session.close();
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
      if (targetType != GeometryInteractionTarget.TargetType.CLOSE_PATH
         || index != 0
         || action != GeometryInteractionAction.CLOSE_PATH
         || (gesture != PointerGesture.RIGHT_CLICK && gesture != PointerGesture.RIGHT_DOUBLE_CLICK)) {
         return false;
      }
      return this.onClosePath(session, context);
   }

   @Override
   public GeometryStage stage(GeometryWorkflowView view) {
      return view.closed()
         ? GeometryStage.adjusting("wall.extrude")
         : GeometryStage.collecting("wall.path").allow(GeometryAction.SUBMODE);
   }

   @Override
   public boolean canFill(GeometrySession session) {
      return session.closed();
   }

   @Override
   public GeometryBuildResult build(GeometrySession session, GeometryActionContext context, FillMode fillMode, int maxBlocks) {
      return GeometryBuildResult.ready(
         WallGenerator.estimateScanCells(session.points(), true, session.extrusion()),
         () -> WallGenerator.generate(session.points(), true, session.extrusion(), maxBlocks)
      );
   }

   @Override
   public Component blockedMessage(GeometrySession session) {
      return Component.translatable("fastformer.geometry.message.fill_blocked_wall");
   }

   @Override
   public GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, BlockPos candidatePoint, Vec3 eye
   ) {
      BlockPos candidate = view.closed() ? null : this.planePoint(points, false, eye, view.view(), candidatePoint);
      boolean closing = !view.closed()
         && points.size() >= 3
         && points.getFirst().equals(hoveredPoint);
      List<BlockPos> previewPoints = this.previewPoints(points, candidate, closing);
      BlockPos previewCandidate = !closing && previewPoints.size() > points.size() ? previewPoints.getLast() : null;
      MutableComponent hint = view.closed()
         ? Component.translatable("fastformer.message.geometry_closed_hint")
         : Component.translatable("fastformer.message.geometry_wall_open_hint");
      Set<BlockPos> confirmedGhost = WallGenerator.generate(points, view.closed(), view.extrusion(), FastPlaceGeometry.PREVIEW_MAX_BLOCKS);
      Set<BlockPos> previewGhost = WallGenerator.generate(previewPoints, view.closed(), view.extrusion(), FastPlaceGeometry.PREVIEW_MAX_BLOCKS);
      return GeometryPreviewPlan.builder(points, hoveredPoint)
         .stage(Component.translatable(this.stage(view).labelKey()))
         .hud(Component.empty(), hint)
         .controlPoints(this.controlPoints(points, view.pointRoles(), candidate, hoveredPoint, view.closed()))
         .blocks(GeometryPreviewBlocks.layers(confirmedGhost, previewGhost))
         .candidate(previewCandidate)
         .interactionTargets(this.interactionTargets(view))
         .build();
   }

   @Override
   public GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, GeometryHit candidateHit, Vec3 eye
   ) {
      BlockPos candidate = candidateHit == null ? null : this.raycastPoint(candidateHit, view.modifierHeld());
      return this.previewPlan(view, points, hoveredPoint, candidate, eye);
   }

   @Override
   public List<GeometryInteractionTarget> interactionTargets(GeometryWorkflowView view) {
      return closeTarget(view.pointLocations(), view.closed());
   }

   private static List<GeometryInteractionTarget> closeTarget(List<Vec3> points, boolean closed) {
      if (closed || points.size() < 3) {
         return List.of();
      }
      Vec3 first = points.getFirst();
      return List.of(GeometryInteractionTarget.closePath(0, first));
   }

   private List<BlockPos> previewPoints(List<BlockPos> points, BlockPos candidate, boolean closing) {
      BlockPos previewPoint = closing && !points.isEmpty() ? points.getFirst() : candidate;
      if (previewPoint == null || points.isEmpty() && previewPoint.equals(BlockPos.ZERO)) {
         return points;
      }
      if (!points.isEmpty() && previewPoint.equals(points.getLast())) {
         return points;
      }
      ArrayList<BlockPos> result = new ArrayList<>(points.size() + 1);
      result.addAll(points);
      result.add(previewPoint);
      return result;
   }

   private List<ControlPoint> controlPoints(
      List<BlockPos> points,
      List<ControlPointRole> roles,
      BlockPos candidate,
      BlockPos hoveredPoint,
      boolean closed
   ) {
      if (points.isEmpty()) {
         return List.of();
      }
      boolean closeable = !closed && points.size() >= 3;
      boolean closing = closeable && points.getFirst().equals(hoveredPoint);
      ArrayList<ControlPoint> result = new ArrayList<>(points.size());
      for (int i = 0; i < points.size(); i++) {
         BlockPos point = points.get(i);
         ControlPointRole role = i < roles.size()
            ? roles.get(i)
            : i == 0
               ? ControlPointRole.PRIMARY
               : ControlPointRole.SECONDARY;
         boolean hovered = i == 0 && closing;
         ControlPoint controlPoint = ControlPoint.of(point, role).withHovered(hovered);
         if (i == 0 && closeable) {
            controlPoint = controlPoint.withFeedback(ControlPointFeedback.closeable());
         }
         result.add(controlPoint);
      }
      return result;
   }

   private BlockPos planePoint(GeometrySession session, GeometryActionContext context, BlockPos fallback) {
      return this.planePoint(session.points(), session.closed(), context.eye(), context.view(), fallback);
   }

   static BlockPos raycastPoint(GeometryHit hit, boolean embedded) {
      return embedded ? hit.blockPos() : hit.blockPos().relative(hit.face());
   }

   private BlockPos planePoint(List<BlockPos> points, boolean closed, Vec3 eye, Vec3 view, BlockPos fallback) {
      if (fallback == null || points.size() < 3 || closed || fallback.equals(points.getFirst())) {
         return fallback;
      }
      Vec3 a = Vec3.atCenterOf(points.get(0));
      Vec3 b = Vec3.atCenterOf(points.get(1));
      Vec3 c = Vec3.atCenterOf(points.get(2));
      return GeometryConstraints.planeFromPoints(a, b, c)
         .flatMap(plane -> GeometryConstraints.rayPlane(eye, view, plane))
         .map(BlockPos::containing)
         .orElse(fallback);
   }
}
