package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PreviewAsyncPolicyTest {
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
