package io.github.fastformer.common.geometry.workflow.plan;

import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflow;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflowView;
import io.github.fastformer.common.geometry.raster.GeometryBuildResult;
import io.github.fastformer.common.geometry.GeometryStage;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Pure planners only receive an immutable view of their registered shape. */
public abstract class ShapeWorkflow<V extends GeometryWorkflowView> implements GeometryWorkflow {
   private final Class<V> viewType;
   protected ShapeWorkflow(Class<V> viewType) { this.viewType = viewType; }

   @Override public final boolean canFill(GeometryWorkflowView view) { return canFillView(viewType.cast(view)); }
   protected abstract boolean canFillView(V view);
   @Override public final GeometryBuildResult build(GeometryWorkflowView view, FillMode fillMode, int maxBlocks) {
      return buildView(viewType.cast(view), fillMode, maxBlocks);
   }
   protected abstract GeometryBuildResult buildView(V view, FillMode fillMode, int maxBlocks);
   @Override public final Component blockedMessage(GeometryWorkflowView view) { return blockedMessageView(viewType.cast(view)); }
   protected abstract Component blockedMessageView(V view);
   @Override public final Component pointBlockedMessage(GeometryWorkflowView view) { return pointBlockedMessageView(viewType.cast(view)); }
   protected Component pointBlockedMessageView(V view) { return Component.empty(); }

   @Override public final GeometryStage stage(GeometryWorkflowView view) { return stageView(viewType.cast(view)); }
   protected abstract GeometryStage stageView(V view);
   @Override public final List<GeometryInteractionTarget> interactionTargets(GeometryWorkflowView view) {
      return interactionTargetsView(viewType.cast(view));
   }
   protected List<GeometryInteractionTarget> interactionTargetsView(V view) { return List.of(); }
   @Override public final GeometryPreviewPlan previewPlan(GeometryWorkflowView view, List<BlockPos> points,
      BlockPos hovered, BlockPos candidate, Vec3 eye) { return previewView(viewType.cast(view), points, hovered, candidate, eye); }
   @Override public final GeometryPreviewPlan previewPlan(GeometryWorkflowView view, List<BlockPos> points,
      BlockPos hovered, GeometryHit candidate, Vec3 eye) { return previewView(viewType.cast(view), points, hovered, candidate, eye); }
   protected GeometryPreviewPlan previewView(V view, List<BlockPos> points, BlockPos hovered, BlockPos candidate, Vec3 eye) {
      return GeometryPreviewPlan.controlPoints(points, hovered);
   }
   protected GeometryPreviewPlan previewView(V view, List<BlockPos> points, BlockPos hovered, GeometryHit candidate, Vec3 eye) {
      return previewView(view, points, hovered, candidate == null ? null : candidate.point(), eye);
   }
}
