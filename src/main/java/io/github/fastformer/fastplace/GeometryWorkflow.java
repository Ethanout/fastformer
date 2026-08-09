package io.github.fastformer.fastplace;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import io.github.fastformer.fastplace.geometry.GeometryBuildResult;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryStage;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import java.util.List;

public interface GeometryWorkflow {
   GeometryMode mode();

   default void onAddPoint(GeometrySession session, GeometryActionContext context, BlockPos point) {
      session.addPoint(point);
   }

   default void onAddPoint(GeometrySession session, GeometryActionContext context, GeometryHit hit) {
      this.onAddPoint(session, context, hit.point());
   }

   default boolean onScroll(GeometrySession session, GeometryActionContext context, int steps) {
      return false;
   }

   default boolean onCycleMode(GeometrySession session, GeometryActionContext context) {
      return false;
   }

   default boolean onGizmoDrag(
      GeometrySession session,
      GeometryActionContext context,
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      int steps
   ) {
      return false;
   }

   default boolean onInteraction(
      GeometrySession session,
      GeometryActionContext context,
      GeometryInteractionTarget.TargetType targetType,
      int index,
      GeometryInteractionAction action,
      PointerGesture gesture
   ) {
      return false;
   }

   default List<GeometryInteractionTarget> interactionTargets(GeometryWorkflowView view) {
      return List.of();
   }

   default boolean onClosePath(GeometrySession session, GeometryActionContext context) {
      return false;
   }

   default boolean confirmsOnRightClick(GeometrySession session) {
      return this.allows(session, GeometryAction.CONFIRM);
   }

   default GeometryStage stage(GeometrySession session) {
      return this.stage(GeometryWorkflowView.from(session));
   }

   default GeometryStage stage(GeometryWorkflowView view) {
      return GeometryStage.collecting(this.mode().name().toLowerCase(java.util.Locale.ROOT));
   }

   default boolean allows(GeometrySession session, GeometryAction action) {
      return this.stage(session).allows(action);
   }

   default boolean allows(GeometryWorkflowView view, GeometryAction action) {
      return this.stage(view).allows(action);
   }

   boolean canFill(GeometrySession session);

   GeometryBuildResult build(GeometrySession session, GeometryActionContext context, FillMode fillMode, int maxBlocks);

   Component blockedMessage(GeometrySession session);

   default Component pointBlockedMessage(GeometrySession session) {
      return Component.empty();
   }

   default Component status(GeometrySession session, GeometryActionContext context) {
      return Component.empty();
   }

   default GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, BlockPos candidatePoint, Vec3 eye
   ) {
      return GeometryPreviewPlan.controlPoints(points, hoveredPoint);
   }

   default GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, GeometryHit candidateHit, Vec3 eye
   ) {
      return this.previewPlan(view, points, hoveredPoint, candidateHit == null ? null : candidateHit.point(), eye);
   }
}
