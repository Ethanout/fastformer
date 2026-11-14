package io.github.fastformer.common.geometry.workflow.plan;

import io.github.fastformer.common.geometry.workflow.CompoundWorkflowView;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.generation.FastPlaceGeometry;

import io.github.fastformer.common.geometry.GeometryMode;

import io.github.fastformer.common.geometry.raster.GeometryBuildResult;
import io.github.fastformer.common.geometry.preview.GeometryPreviewGuides;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.preview.GeometryPreviewBlocks;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.GeometryStage;
import io.github.fastformer.common.geometry.generation.shape.LoftGenerator;
import io.github.fastformer.common.geometry.generation.shape.SweepGenerator;
import io.github.fastformer.common.geometry.generation.source.BlockGenerationResult;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;

public final class CompoundWorkflow extends ShapeWorkflow<CompoundWorkflowView> {
   public CompoundWorkflow() { super(CompoundWorkflowView.class); }

   @Override
   public GeometryMode mode() {
      return GeometryMode.COMPOUND;
   }

   @Override
   protected GeometryStage stageView(CompoundWorkflowView view) {
      return view.pointCount() >= 3
         ? GeometryStage.collectingWithScroll("compound.variant").allow(GeometryAction.CONFIRM)
         : GeometryStage.collecting("compound.points");
   }

   @Override
   protected boolean canFillView(CompoundWorkflowView view) {
      return view.pointCount() >= 3;
   }

   @Override
   protected GeometryBuildResult buildView(CompoundWorkflowView view, FillMode fillMode, int maxBlocks) {
      int shapeVariant = view.compoundShapeVariant();
      List<BlockPos> points = view.pointLocations().stream().map(BlockPos::containing).toList();
      long scanCells = estimateScanCells(points, shapeVariant);
      return GeometryBuildResult.ready(
         scanCells,
         maxBlocks,
         () -> generateResult(points, shapeVariant, fillMode, maxBlocks)
      );
   }

   @Override
   protected Component blockedMessageView(CompoundWorkflowView view) {
      return Component.translatable("fastformer.geometry.message.fill_blocked_compound");
   }

   @Override
   protected GeometryPreviewPlan previewView(
      CompoundWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, BlockPos candidatePoint, Vec3 eye
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
