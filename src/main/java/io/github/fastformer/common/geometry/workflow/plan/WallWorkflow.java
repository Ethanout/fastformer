package io.github.fastformer.common.geometry.workflow.plan;

import io.github.fastformer.common.geometry.workflow.WallWorkflowView;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.raster.GeometryBuildResult;
import io.github.fastformer.common.geometry.model.GeometryConstraints;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.preview.GeometryPreviewBlocks;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.GeometryStage;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import io.github.fastformer.common.geometry.controlpoint.ControlPoint;
import io.github.fastformer.common.geometry.controlpoint.ControlPointFeedback;
import io.github.fastformer.common.geometry.ControlPointRole;
import io.github.fastformer.common.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.geometry.generation.shape.WallGenerator;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.common.geometry.interaction.PointerGesture;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;

public final class WallWorkflow extends ShapeWorkflow<WallWorkflowView> {
   public WallWorkflow() { super(WallWorkflowView.class); }

   @Override
   public GeometryMode mode() {
      return GeometryMode.WALL;
   }

   @Override
   protected GeometryStage stageView(WallWorkflowView view) {
      return view.closed()
         ? GeometryStage.adjusting("wall.extrude")
         : GeometryStage.collecting("wall.path").allow(GeometryAction.SUBMODE);
   }

   @Override
   protected boolean canFillView(WallWorkflowView view) {
      return view.closed();
   }

   @Override
   protected GeometryBuildResult buildView(WallWorkflowView view, FillMode fillMode, int maxBlocks) {
      List<BlockPos> points = view.pointLocations().stream().map(BlockPos::containing).toList();
      BlockPos extrusion = view.extrusion().immutable();
      return GeometryBuildResult.ready(
         WallGenerator.estimateScanCells(points, true, extrusion),
         maxBlocks,
         () -> WallGenerator.generateResult(points, true, extrusion, maxBlocks)
      );
   }

   @Override
   protected Component blockedMessageView(WallWorkflowView view) {
      return Component.translatable("fastformer.geometry.message.fill_blocked_wall");
   }

   @Override
   protected GeometryPreviewPlan previewView(
      WallWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, BlockPos candidatePoint, Vec3 eye
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
         .placementReady(view.closed())
         .candidate(previewCandidate)
         .interactionTargets(this.interactionTargets(view))
         .build();
   }

   @Override
   protected GeometryPreviewPlan previewView(
      WallWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, GeometryHit candidateHit, Vec3 eye
   ) {
      BlockPos candidate = candidateHit == null ? null : this.raycastPoint(candidateHit, view.modifierHeld());
      return this.previewPlan(view, points, hoveredPoint, candidate, eye);
   }

   @Override
   protected List<GeometryInteractionTarget> interactionTargetsView(WallWorkflowView view) {
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

   public static BlockPos raycastPoint(GeometryHit hit, boolean embedded) {
      return embedded ? hit.blockPos() : hit.blockPos().relative(hit.face());
   }

   public static BlockPos planePoint(List<BlockPos> points, boolean closed, Vec3 eye, Vec3 view, BlockPos fallback) {
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
