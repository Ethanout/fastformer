package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Budget-gate tests. {@code OperationWorkspaceValidator} and
 * {@code WorkspacePreviewComposer} both call this module, so these tests cover the
 * production cap rule itself.
 */
class WorkspaceGeometryBudgetTest {
   private static Map<BlockPos, String> sparseAlongX() {
      return Map.of(new BlockPos(0, 64, 0), "left", new BlockPos(100, 64, 0), "right");
   }

   private static WorkspaceGeometryCost.Cost sparseWith(WorkspaceTransform transform) {
      return WorkspaceGeometryCost.of(sparseAlongX().keySet(), transform);
   }

   private static WorkspaceGeometryCost.Cost repeated(long axisCells) {
      OperationStackRegion repeats = new OperationStackRegion(BlockPos.ZERO, new BlockPos((int)axisCells - 1, 0, 0));
      return WorkspaceGeometryCost.of(
         Map.of(BlockPos.ZERO, "a").keySet(), new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO, repeats)
      );
   }

   private static WorkspaceGeometryCost.Cost projected(long projectedUpperBound) {
      return new WorkspaceGeometryCost.Cost(1L, 0L, 1L, 1L, projectedUpperBound);
   }

   @Test
   void sparseNonEmptyPartIsAcceptedWithItsOccupiedCount() {
      // BUG-L: a sparse part that really submits two voxels. The old rule billed the whole
      // bounding volume of 101 cells and rejected the submission.
      Map<BlockPos, String> sparse = sparseAlongX();
      OccupiedBlockBounds bounds = OccupiedBlockBounds.from(sparse.keySet()).orElseThrow();
      long boundingVolume = (long)bounds.width(AxisGizmo.Axis.X)
         * bounds.width(AxisGizmo.Axis.Y)
         * bounds.width(AxisGizmo.Axis.Z);
      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(sparse.keySet(), WorkspaceTransform.IDENTITY);

      assertEquals(2L, cost.sourceCount(), "the part submits two real voxels, so it is not an empty plan");
      assertEquals(101L, boundingVolume, "the bounding volume spans 101 cells");
      assertTrue(cost.canProduceVoxels());
      assertTrue(WorkspaceGeometryBudget.fits(100L, cost), "the real spend of 2 voxels fits the cap");
      assertEquals(
         2L,
         WorkspaceGeometryBudget.assess(100L, List.of(cost)).plannedUpperBound(),
         "an accepted sparse part spends its occupied count"
      );
   }

   @Test
   void translatingSparsePartFits() {
      WorkspaceTransform translated = WorkspaceTransform.IDENTITY.withTranslation(new Vec3(400, 0, 0));

      assertTrue(WorkspaceGeometryBudget.fits(100L, sparseWith(translated)));
   }

   @Test
   void realUpscaleSpendsItsScanVolumeAndRejects() {
      WorkspaceGeometryCost.Cost upscaled = WorkspaceGeometryCost.of(
         Map.of(new BlockPos(0, 0, 0), "a", new BlockPos(1, 0, 0), "b").keySet(),
         WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 500.0)
      );

      assertEquals(1000L, upscaled.projectedUpperBound());
      assertFalse(WorkspaceGeometryBudget.fits(100L, upscaled), "a real 1000-cell scan must stay limited");
   }

   @Test
   void repetitionBoundaryIsInclusive() {
      assertTrue(WorkspaceGeometryBudget.fits(100L, repeated(100L)), "100 cells fit a cap of 100");
      assertFalse(WorkspaceGeometryBudget.fits(100L, repeated(101L)), "101 cells must reject");
   }

   @Test
   void sparsePartStillSpendsItsRepeatCells() {
      OperationStackRegion atLimit = new OperationStackRegion(BlockPos.ZERO, new BlockPos(49, 0, 0));
      OperationStackRegion overLimit = new OperationStackRegion(BlockPos.ZERO, new BlockPos(50, 0, 0));

      assertEquals(
         100L,
         WorkspaceGeometryBudget.assess(
            100L, List.of(sparseWith(new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO, atLimit)))
         ).plannedUpperBound(),
         "2 voxels over 50 cells spend exactly 100"
      );
      assertFalse(
         WorkspaceGeometryBudget.fits(100L, sparseWith(new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO, overLimit))),
         "2 voxels over 51 cells must reject"
      );
   }

   @Test
   void accumulatesAcrossParts() {
      assertTrue(WorkspaceGeometryBudget.assess(100L, List.of(projected(60L), projected(40L))).fits());
      assertFalse(WorkspaceGeometryBudget.assess(100L, List.of(projected(60L), projected(41L))).fits());
   }

   @Test
   void noGeometryRejects() {
      WorkspaceGeometryCost.Cost noRepeats = new WorkspaceGeometryCost.Cost(2L, 0L, 2L, 0L, 0L);

      assertFalse(noRepeats.canProduceVoxels());
      assertFalse(WorkspaceGeometryBudget.fits(100L, noRepeats));
      assertFalse(WorkspaceGeometryBudget.fits(100L, (WorkspaceGeometryCost.Cost)null));
      assertFalse(WorkspaceGeometryBudget.assess(
         100L, Collections.singletonList((WorkspaceGeometryCost.Cost)null)
      ).fits());
   }

   @Test
   void nonPositiveCapRejects() {
      assertFalse(WorkspaceGeometryBudget.assess(0L, List.of(projected(1L))).fits());
      assertFalse(WorkspaceGeometryBudget.assess(-5L, List.of(projected(1L))).fits());
      assertFalse(WorkspaceGeometryBudget.assess(100L, null).fits());
   }

   @Test
   void saturatedCostRejectsInsteadOfWrapping() {
      assertFalse(WorkspaceGeometryBudget.assess(100L, List.of(projected(Long.MAX_VALUE))).fits());
   }

   @Test
   void emptyPlanFitsWithNoSpend() {
      WorkspaceGeometryBudget.Assessment assessment = WorkspaceGeometryBudget.assess(100L, List.of());

      assertTrue(assessment.fits());
      assertEquals(0L, assessment.plannedUpperBound());
   }
}
