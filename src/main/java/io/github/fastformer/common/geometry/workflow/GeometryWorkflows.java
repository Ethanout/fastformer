package io.github.fastformer.common.geometry.workflow;

import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflowView;
import io.github.fastformer.common.geometry.workflow.plan.*;

import java.util.List;
import java.util.Comparator;
import io.github.fastformer.core.registry.Registry;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryStage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class GeometryWorkflows {
   private static final Registry<GeometryMode, GeometryWorkflow> WORKFLOWS = Registry.create("geometry planners");

   static {
      register(new WallWorkflow());
      register(new PolyhedronWorkflow());
      register(new ConePrismWorkflow());
      register(new CompoundWorkflow());
      register(new ConvexPolyhedronWorkflow());
      for (GeometryMode mode : GeometryMode.values()) get(mode);
      WORKFLOWS.freeze();
   }

   private GeometryWorkflows() {
   }

   /** Query access intentionally exposes no mutable draft commands. */
   public static GeometryWorkflow get(GeometryMode mode) {
      return WORKFLOWS.get(mode).orElseThrow(
         () -> new IllegalArgumentException("No geometry workflow registered for " + mode));
   }

   /** Server mode assembly uses the same frozen registrations as shared planning. */
   public static List<GeometryWorkflow> registered() {
      return WORKFLOWS.ordered(Comparator.comparingInt(workflow -> workflow.mode().ordinal())).toList();
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
      WORKFLOWS.register(workflow.mode(), workflow);
   }
}
