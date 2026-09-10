package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.fastplace.*;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.geometry.GeometryBuildResult;
import io.github.fastformer.fastplace.geometry.GeometryPreviewGuides;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryPreviewBlocks;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryStage;
import io.github.fastformer.fastplace.geometry.generation.LoftGenerator;
import io.github.fastformer.fastplace.geometry.generation.SweepGenerator;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;

public final class CompoundWorkflow implements GeometryWorkflow {
   @Override
   public GeometryMode mode() {
      return GeometryMode.COMPOUND;
   }

   @Override
   public boolean onScroll(GeometrySession session, GeometryActionContext context, int steps) {
      if (!this.allows(session, GeometryAction.SCALAR_ADJUST)) {
         return false;
      }
      session.cycleCompoundShapeVariant(steps);
      return true;
   }

   @Override
   public GeometryStage stage(GeometryWorkflowView view) {
      return view.pointCount() >= 3
         ? GeometryStage.collectingWithScroll("compound.variant").allow(GeometryAction.CONFIRM)
         : GeometryStage.collecting("compound.points");
   }

   @Override
   public boolean canFill(GeometrySession session) {
      return session.points().size() >= 3;
   }

   @Override
   public GeometryBuildResult build(GeometrySession session, GeometryActionContext context, FillMode fillMode, int maxBlocks) {
      int shapeVariant = session.compoundShapeVariant();
      List<BlockPos> points = List.copyOf(session.points());
      long scanCells = estimateScanCells(points, shapeVariant);
      return GeometryBuildResult.ready(
         scanCells,
         maxBlocks,
         () -> generateResult(points, shapeVariant, fillMode, maxBlocks)
      );
   }

   @Override
   public Component blockedMessage(GeometrySession session) {
      return Component.translatable("fastformer.geometry.message.fill_blocked_compound");
   }

   @Override
   public GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, BlockPos candidatePoint, Vec3 eye
   ) {
      MutableComponent hint = Component.translatable("fastformer.message.geometry_open_hint");
      int shapeVariant = view.compoundShapeVariant();
      long scanCells = estimateScanCells(points, shapeVariant);
      Set<BlockPos> ghost = GeometryPreviewBlocks.generatedOrControlPoints(
         points,
         scanCells,
         FastPlaceGeometry.PREVIEW_MAX_BLOCKS,
         () -> generate(points, shapeVariant, view.fillMode(), FastPlaceGeometry.PREVIEW_MAX_BLOCKS)
      );
      return GeometryPreviewPlan.builder(points, hoveredPoint)
         .stage(Component.translatable(this.stage(view).labelKey()))
         .hud(variantName(view.compoundShapeVariant()), hint)
         .ghost(ghost)
         .placementReady(points.size() >= 3 && scanCells <= FastPlaceGeometry.PREVIEW_MAX_BLOCKS)
         .candidate(candidatePoint)
         .controlPoints(GeometryPreviewGuides.confirmedPoints(view.pointLocations(), view.pointRoles()))
         .build();
   }

   private static MutableComponent variantName(int shapeVariant) {
      return Component.translatable(
         Math.floorMod(shapeVariant, 2) == 0
            ? "fastformer.geometry.compound.sweep"
            : "fastformer.geometry.compound.loft"
      );
   }

   private static Set<BlockPos> generate(List<BlockPos> points, int shapeVariant, FillMode fillMode, int maxBlocks) {
      return Math.floorMod(shapeVariant, 2) == 0
         ? SweepGenerator.generate(points, fillMode, maxBlocks)
         : LoftGenerator.generate(points, maxBlocks);
   }

   private static BlockGenerationResult generateResult(
      List<BlockPos> points, int shapeVariant, FillMode fillMode, int maxBlocks
   ) {
      return Math.floorMod(shapeVariant, 2) == 0
         ? SweepGenerator.generateResult(points, fillMode, maxBlocks)
         : LoftGenerator.generateResult(points, maxBlocks);
   }

   private static long estimateScanCells(List<BlockPos> points, int shapeVariant) {
      return Math.floorMod(shapeVariant, 2) == 0
         ? SweepGenerator.estimateScanCells(points)
         : LoftGenerator.estimateScanCells(points);
   }
}
