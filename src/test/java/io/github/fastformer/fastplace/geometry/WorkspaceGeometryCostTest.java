package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cost-model tests for ARCH-2: the write bound must stay honest under scaling. */
class WorkspaceGeometryCostTest {
   private static Map<BlockPos, String> sparseAlongX() {
      return Map.of(
         new BlockPos(0, 64, 0), "left",
         new BlockPos(100, 64, 0), "right"
      );
   }

   @Test
   void identitySparsePartIsExactAndScansNothing() {
      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(sparseAlongX().keySet(), WorkspaceTransform.IDENTITY);

      assertEquals(2L, cost.sourceCount());
      assertEquals(0L, cost.scanVolume());
      assertEquals(2L, cost.writtenUpperBound());
      assertEquals(1L, cost.repeatCells());
      assertEquals(2L, cost.projectedUpperBound());
      assertTrue(cost.canProduceVoxels());
   }

   @Test
   void translationOnlySparsePartIsExactAndScansNothing() {
      WorkspaceTransform translated = WorkspaceTransform.IDENTITY.withTranslation(new Vec3(500, 0, -500));

      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(sparseAlongX().keySet(), translated);

      assertEquals(0L, cost.scanVolume(), "pure translation cannot change an occupied width");
      assertEquals(2L, cost.writtenUpperBound());
   }

   @Test
   void scaleThatKeepsEveryWidthIsStillExact() {
      Map<BlockPos, String> source = Map.of(
         new BlockPos(0, 0, 0), "a",
         new BlockPos(1, 0, 0), "b",
         new BlockPos(2, 0, 0), "c"
      );
      WorkspaceTransform negligible = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 1.0001);

      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(source.keySet(), negligible);

      assertEquals(0L, cost.scanVolume(), "a scale that rounds back to the same width runs no scan");
      assertEquals(3L, cost.writtenUpperBound());
   }

   @Test
   void upscaleReplicatesSoTheWriteBoundIsTheScanVolume() {
      Map<BlockPos, String> source = Map.of(
         new BlockPos(0, 0, 0), "a",
         new BlockPos(1, 0, 0), "b"
      );
      WorkspaceTransform upscaled = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 500.0);

      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(source.keySet(), upscaled);

      assertEquals(2L, cost.sourceCount());
      assertEquals(1000L, cost.scanVolume());
      assertEquals(1000L, cost.writtenUpperBound(), "an upscale copies voxels, so 2 is not a bound");
      assertEquals(1000L, cost.projectedUpperBound());
   }

   @Test
   void downscaleBoundsWritesByTheSampledCellsNotTheSourceCount() {
      Map<BlockPos, String> dense = Map.of(
         new BlockPos(0, 64, 0), "a",
         new BlockPos(1, 64, 0), "b",
         new BlockPos(2, 64, 0), "c"
      );
      WorkspaceTransform shrink = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 1.0 / 3.0);

      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(dense.keySet(), shrink);

      assertEquals(3L, cost.sourceCount());
      assertEquals(1L, cost.scanVolume());
      assertEquals(1L, cost.writtenUpperBound(), "a downscale can only write into the sampled cells");
   }

   @Test
   void roundedScaleUsesHalfUpRoundingLikeTheSampler() {
      Map<BlockPos, String> dense = Map.of(
         new BlockPos(0, 0, 0), "a",
         new BlockPos(1, 0, 0), "b",
         new BlockPos(2, 0, 0), "c",
         new BlockPos(3, 0, 0), "d",
         new BlockPos(4, 0, 0), "e"
      );
      // Width 5 with scale 0.5 is exactly 2.5. Math.round gives 3, Math.rint would give 2.
      WorkspaceTransform half = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 0.5);

      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(dense.keySet(), half);

      assertEquals(3L, cost.scanVolume(), "the estimator must round 2.5 up, like the sampler");
      assertEquals(3L, cost.writtenUpperBound());
   }

   @Test
   void repeatCellsMultiplyTheBound() {
      OperationStackRegion repeats = new OperationStackRegion(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9));
      WorkspaceTransform repeated = new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO, repeats);

      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(
         Map.of(BlockPos.ZERO, "a").keySet(), repeated
      );

      assertEquals(1000L, cost.repeatCells());
      assertEquals(1L, cost.writtenUpperBound());
      assertEquals(1000L, cost.projectedUpperBound());
   }

   @Test
   void hostileScaleSaturatesInsteadOfWrapping() {
      Map<BlockPos, String> wide = Map.of(
         new BlockPos(0, 0, 0), "a",
         new BlockPos(2_000_000_000, 2_000_000_000, 0), "b"
      );
      WorkspaceTransform hostile = WorkspaceTransform.IDENTITY
         .withScale(AxisGizmo.Axis.X, 1024.0)
         .withScale(AxisGizmo.Axis.Y, 1024.0);

      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(wide.keySet(), hostile);

      assertEquals(Long.MAX_VALUE, cost.scanVolume());
      assertEquals(Long.MAX_VALUE, cost.projectedUpperBound());
   }

   @Test
   void emptyOrMissingInputHasNoCost() {
      assertNull(WorkspaceGeometryCost.of(Map.<BlockPos, String>of().keySet(), WorkspaceTransform.IDENTITY));
      assertNull(WorkspaceGeometryCost.of(null, WorkspaceTransform.IDENTITY));
      assertNull(WorkspaceGeometryCost.of(Map.of(BlockPos.ZERO, "a").keySet(), null));
   }
}
