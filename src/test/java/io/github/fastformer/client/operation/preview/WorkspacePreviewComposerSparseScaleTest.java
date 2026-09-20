package io.github.fastformer.client.operation.preview;

import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.WorkspaceGeometryCost;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for BUG-G: a sparse prism that shrinks to a single empty sample must return
 * an empty map, not throw {@code NoSuchElementException}. The last test also pins the half-up
 * rounding that keeps the sampler and the cost estimator in agreement.
 */
class WorkspacePreviewComposerSparseScaleTest {
   private static Map<BlockPos, String> sparseAlongX() {
      return Map.of(
         new BlockPos(0, 64, 0), "left",
         new BlockPos(2, 64, 0), "right"
      );
   }

   @Test
   void shrinkingSparsePrismToSingleEmptySampleReturnsEmptyMapWithoutThrowing() {
      WorkspaceTransform shrink = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 1.0 / 3.0);

      Map<BlockPos, String> resolved = assertDoesNotThrow(() ->
         WorkspacePreviewComposer.resolveValues(sparseAlongX(), shrink));

      assertTrue(resolved.isEmpty(), "an empty sampling result must propagate as an empty map");
   }

   @Test
   void shrinkingSparsePrismStaysEmptyThroughRenderingPath() {
      WorkspaceTransform shrink = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 1.0 / 3.0);
      Map<BlockPos, String> source = sparseAlongX();

      assertTrue(WorkspacePreviewComposer.canResolveForRendering(source, shrink));
      assertTrue(assertDoesNotThrow(() -> WorkspacePreviewComposer.resolveValues(source, shrink)).isEmpty());
   }

   @Test
   void shrinkingToEmptyProducesNoOutputOnTheShrunkAxis() {
      WorkspaceTransform shrink = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 1.0 / 4.0);
      Map<BlockPos, String> resolved = assertDoesNotThrow(() ->
         WorkspacePreviewComposer.resolveValues(sparseAlongX(), shrink));
      assertTrue(resolved.isEmpty());

      // A single-cell axis width cannot shrink below one, so the sample stays occupied.
      for (AxisGizmo.Axis axis : new AxisGizmo.Axis[]{AxisGizmo.Axis.Y, AxisGizmo.Axis.Z}) {
         WorkspaceTransform other = WorkspaceTransform.IDENTITY.withScale(axis, 1.0 / 4.0);
         assertEquals(
            sparseAlongX(),
            assertDoesNotThrow(() -> WorkspacePreviewComposer.resolveValues(sparseAlongX(), other)),
            "axis " + axis + " has width 1 and cannot shrink"
         );
      }
   }

   @Test
   void repeatedShrinkingToEmptyStillReturnsEmptyMap() {
      OperationStackRegion repeats = OperationStackRegion.origin().repeat(AxisGizmo.Axis.Y, 1, 3);
      WorkspaceTransform shrinkAndRepeat = new WorkspaceTransform(
         Vec3.ZERO, Vec3.ZERO, repeats, BlockPos.ZERO, new Vec3(0.25, 1.0, 1.0)
      );

      Map<BlockPos, String> resolved = assertDoesNotThrow(() ->
         WorkspacePreviewComposer.resolveValues(sparseAlongX(), shrinkAndRepeat));

      assertTrue(resolved.isEmpty());
   }

   @Test
   void shrinkingDensePrismKeepsOneSampledVoxel() {
      WorkspaceTransform shrink = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 1.0 / 3.0);
      Map<BlockPos, String> dense = Map.of(
         new BlockPos(0, 64, 0), "a",
         new BlockPos(1, 64, 0), "b",
         new BlockPos(2, 64, 0), "c"
      );

      Map<BlockPos, String> resolved = WorkspacePreviewComposer.resolveValues(dense, shrink);

      assertEquals(1, resolved.size());
   }

   @Test
   void halfUpRoundingKeepsEstimatorAndSamplerInAgreement() {
      Map<BlockPos, String> dense = Map.of(
         new BlockPos(0, 0, 0), "a",
         new BlockPos(1, 0, 0), "b",
         new BlockPos(2, 0, 0), "c",
         new BlockPos(3, 0, 0), "d",
         new BlockPos(4, 0, 0), "e"
      );
      WorkspaceTransform half = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 0.5);

      // Width 5 with scale 0.5 is exactly 2.5. Math.round gives 3, Math.rint would give 2.
      Map<BlockPos, String> sampled = WorkspacePreviewComposer.scaleValues(dense, half.scale());
      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(dense.keySet(), half);

      assertEquals(3, sampled.size(), "the sampler must round 2.5 up");
      assertEquals(3L, cost.scanVolume(), "the estimator must round 2.5 up too");
      assertEquals((long)sampled.size(), cost.writtenUpperBound(), "both paths must agree");
   }

   @Test
   void identityAndTranslationPreserveSparseSourceWithoutScaling() {
      Map<BlockPos, String> source = sparseAlongX();

      assertEquals(source, WorkspacePreviewComposer.resolveValues(source, WorkspaceTransform.IDENTITY));
      assertEquals(
         source,
         WorkspacePreviewComposer.resolveValues(source, WorkspaceTransform.IDENTITY.withTranslation(new Vec3(0, 0, 0)))
      );
   }
}
