package io.github.fastformer.client.operation.preview;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkspacePreviewComposerTest {
   @Test
   void repeatUsesOccupiedIntegerWidthAndSameAxisCountsLinearly() {
      OperationStackRegion repeats = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 3)
         .repeat(AxisGizmo.Axis.X, 1, 1);
      WorkspaceTransform transform = new WorkspaceTransform(new Vec3(10, 0, 0), Vec3.ZERO, repeats);

      Map<BlockPos, String> resolved = WorkspacePreviewComposer.resolveValues(
         Map.of(new BlockPos(0, 0, 0), "block"), transform
      );

      assertEquals(
         java.util.Set.of(
            new BlockPos(10, 0, 0), new BlockPos(11, 0, 0), new BlockPos(12, 0, 0),
            new BlockPos(13, 0, 0), new BlockPos(14, 0, 0)
         ),
         resolved.keySet()
      );
   }

   @Test
   void higherPartIdWinsAndOverlapPositionsAreReported() {
      LinkedHashMap<Integer, Map<BlockPos, String>> parts = new LinkedHashMap<>();
      parts.put(7, Map.of(BlockPos.ZERO, "high"));
      parts.put(2, Map.of(BlockPos.ZERO, "low", new BlockPos(1, 0, 0), "side"));

      WorkspacePreviewComposer.ComposedValues<String> composed = WorkspacePreviewComposer.composeValues(parts);

      assertEquals("high", composed.values().get(BlockPos.ZERO));
      assertEquals("side", composed.values().get(new BlockPos(1, 0, 0)));
      assertEquals(java.util.Set.of(BlockPos.ZERO), composed.overlaps());
   }

   @Test
   void nearestNeighbourStretchFillsEveryTargetLayer() {
      Map<BlockPos, String> source = Map.of(
         new BlockPos(0, 0, 0), "left",
         new BlockPos(1, 0, 0), "right"
      );

      Map<BlockPos, String> stretched = WorkspacePreviewComposer.scaleValues(
         source, new Vec3(3.0, 1.0, 1.0)
      );

      assertEquals(6, stretched.size());
      assertEquals(
         java.util.Set.of(
            new BlockPos(-2, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 0, 0),
            new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0)
         ),
         stretched.keySet()
      );
   }

   @Test
   void sourceMaskClearsWithoutRetainingOldPositions() {
      SourceBlockRenderMask mask = new SourceBlockRenderMask();
      mask.replace(java.util.Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)));
      assertTrue(mask.contains(BlockPos.ZERO));

      mask.clear();

      assertTrue(mask.positions().isEmpty());
   }

   @Test
   void refusesWorkspacePreviewThatWouldExpandPastTheClientLimit() {
      OperationStackRegion repeats = new OperationStackRegion(
         new BlockPos(-128, -128, -128), new BlockPos(128, 128, 128)
      );
      WorkspaceTransform transform = new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO, repeats);

      assertTrue(!WorkspacePreviewComposer.canResolveForRendering(
         Map.of(BlockPos.ZERO, "block"), transform
      ));
   }

   @Test
   void sparseUnscaledWorkspaceUsesOccupiedCountInsteadOfBoundingVolume() {
      Map<BlockPos, String> sparse = Map.of(
         BlockPos.ZERO, "first",
         new BlockPos(1_000_000, 0, 0), "second"
      );
      WorkspaceTransform translated = WorkspaceTransform.IDENTITY.withTranslation(new Vec3(1, 0, 0));

      assertTrue(WorkspacePreviewComposer.canResolveForRendering(sparse, translated));
   }
}
