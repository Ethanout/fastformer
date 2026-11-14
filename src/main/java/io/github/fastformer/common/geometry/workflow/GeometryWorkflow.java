package io.github.fastformer.common.geometry.workflow;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflowView;

import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.GeometryBuildResult;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.GeometryStage;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionTarget;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Shared geometry queries consume immutable inputs and cannot mutate a server draft. */
public interface GeometryWorkflow {
   GeometryMode mode();

   boolean canFill(GeometryWorkflowView view);
   GeometryBuildResult build(GeometryWorkflowView view, FillMode fillMode, int maxBlocks);
   Component blockedMessage(GeometryWorkflowView view);
   default Component pointBlockedMessage(GeometryWorkflowView view) { return Component.empty(); }

   default List<GeometryInteractionTarget> interactionTargets(GeometryWorkflowView view) {
      return List.of();
   }

   default GeometryStage stage(GeometryWorkflowView view) {
      return GeometryStage.collecting(this.mode().name().toLowerCase(java.util.Locale.ROOT));
   }

   default boolean allows(GeometryWorkflowView view, GeometryAction action) {
      return this.stage(view).allows(action);
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
