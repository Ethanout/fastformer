package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.fastplace.geometry.GeometryHit;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryStage;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionTarget;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Checks the registry boundary once; concrete workflows cannot query another shape's fields. */
public abstract class ShapeWorkflow<V extends GeometryWorkflowView> implements GeometryWorkflow {
   private final Class<V> viewType;

   protected ShapeWorkflow(Class<V> viewType) { this.viewType = viewType; }

   @Override public final GeometryStage stage(GeometryWorkflowView view) { return stageView(viewType.cast(view)); }
   protected abstract GeometryStage stageView(V view);

   @Override public final List<GeometryInteractionTarget> interactionTargets(GeometryWorkflowView view) {
      return interactionTargetsView(viewType.cast(view));
   }
   protected List<GeometryInteractionTarget> interactionTargetsView(V view) { return List.of(); }

   @Override public final GeometryPreviewPlan previewPlan(GeometryWorkflowView view, List<BlockPos> points,
      BlockPos hovered, BlockPos candidate, Vec3 eye) {
      return previewView(viewType.cast(view), points, hovered, candidate, eye);
   }
   @Override public final GeometryPreviewPlan previewPlan(GeometryWorkflowView view, List<BlockPos> points,
      BlockPos hovered, GeometryHit candidate, Vec3 eye) {
      return previewView(viewType.cast(view), points, hovered, candidate, eye);
   }
   protected GeometryPreviewPlan previewView(V view, List<BlockPos> points, BlockPos hovered, BlockPos candidate, Vec3 eye) {
      return GeometryPreviewPlan.controlPoints(points, hovered);
   }
   protected GeometryPreviewPlan previewView(V view, List<BlockPos> points, BlockPos hovered, GeometryHit candidate, Vec3 eye) {
      return previewView(view, points, hovered, candidate == null ? null : candidate.point(), eye);
   }
}
