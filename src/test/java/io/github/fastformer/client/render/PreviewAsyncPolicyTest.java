package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PreviewAsyncPolicyTest {
   @Test
   void shellBecomesLightweightBeforeLargeSynchronousMeshWork() {
      assertEquals(false, PreviewAsyncPolicy.useLightweightShell(100_000));
      assertEquals(true, PreviewAsyncPolicy.useLightweightShell(100_001));
   }
   @Test
   void fullPreviewStopsAtTheScanLimitForBothPlanesAndVolumes() {
      assertEquals(false, PreviewAsyncPolicy.useOutlineOnly(
         List.of(BlockPos.ZERO, new BlockPos(999, 0, 99)), PreviewAsyncPolicy.Workload.PLANE));
      assertEquals(true, PreviewAsyncPolicy.useOutlineOnly(
         List.of(BlockPos.ZERO, new BlockPos(1000, 0, 99)), PreviewAsyncPolicy.Workload.PLANE));
      assertEquals(true, PreviewAsyncPolicy.useOutlineOnly(
         List.of(BlockPos.ZERO, new BlockPos(99, 99, 99)), PreviewAsyncPolicy.Workload.VOLUME));
      assertEquals(false, PreviewAsyncPolicy.useOutlineOnly(
         List.of(BlockPos.ZERO, new BlockPos(3, 3, 3)), PreviewAsyncPolicy.Workload.VOLUME));
   }

   @Test
   void oversizedPreviewSkipsSolidGenerationBeforeBlocksAreMaterialized() {
      List<BlockPos> oversizedPlane = List.of(BlockPos.ZERO, new BlockPos(1000, 0, 99));

      assertEquals(false, PreviewAsyncPolicy.shouldRenderSolidFallback(
         oversizedPlane, PreviewAsyncPolicy.Workload.PLANE
      ));
   }

   @Test
   void ordinaryPlaneRemainsImmediate() {
      List<BlockPos> points = List.of(BlockPos.ZERO, new BlockPos(99, 0, 0), new BlockPos(99, 0, 99));

      assertEquals(true, PreviewAsyncPolicy.generateSynchronously(points, PreviewAsyncPolicy.Workload.PLANE));
   }

   @Test
   void genuinelyLargePlaneUsesBackgroundGeneration() {
      List<BlockPos> points = List.of(BlockPos.ZERO, new BlockPos(199, 0, 0), new BlockPos(199, 0, 199));

      assertEquals(false, PreviewAsyncPolicy.generateSynchronously(points, PreviewAsyncPolicy.Workload.PLANE));
   }

   @Test
   void gridThresholdMatchesGenerationThreshold() {
      assertEquals(true, PreviewAsyncPolicy.meshSynchronously(16_000));
      assertEquals(false, PreviewAsyncPolicy.meshSynchronously(16_001));
   }
}
