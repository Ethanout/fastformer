package io.github.fastformer.fastplace;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryStage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class GeometryWorkflows {
   private static final Map<GeometryMode, GeometryWorkflow> WORKFLOWS = new EnumMap<>(GeometryMode.class);

   static {
      register(new WallWorkflow());
      register(new PolyhedronWorkflow());
      register(new ConePrismWorkflow());
      register(new CompoundWorkflow());
      register(new ConvexPolyhedronWorkflow());
   }

   private GeometryWorkflows() {
   }

   public static GeometryWorkflow get(GeometryMode mode) {
      GeometryWorkflow workflow = WORKFLOWS.get(mode);
      if (workflow == null) {
         throw new IllegalArgumentException("No geometry workflow registered for " + mode);
      }
      return workflow;
   }

   public static boolean allows(GeometryWorkflowView view, GeometryAction action) {
      return stage(view).allows(action);
   }

   public static GeometryStage stage(GeometryWorkflowView view) {
      return get(view.mode()).stage(view);
   }

   public static GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, BlockPos candidatePoint, Vec3 eye
   ) {
      return get(view.mode()).previewPlan(view, points, hoveredPoint, candidatePoint, eye);
   }

   public static GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, GeometryHit candidateHit, Vec3 eye
   ) {
      return get(view.mode()).previewPlan(view, points, hoveredPoint, candidateHit, eye);
   }

   private static void register(GeometryWorkflow workflow) {
      WORKFLOWS.put(workflow.mode(), workflow);
   }
}
