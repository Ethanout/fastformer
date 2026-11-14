package io.github.fastformer.common.geometry.raster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.common.geometry.generation.source.BlockGenerationResult;
import io.github.fastformer.common.geometry.generation.source.LineGenerator;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class GeometryBuildResultTest {
   @Test
   void legacyGenerationIsLazyAndConvertedAtTheWorkflowBoundary() {
      AtomicInteger calls = new AtomicInteger();
      GeometryBuildResult build = GeometryBuildResult.readyLegacy(3_000_000L, 3L, () -> {
         calls.incrementAndGet();
         return LineGenerator.generate(BlockPos.ZERO, new BlockPos(2, 0, 0), 2);
      });

      assertTrue(build.ready());
      assertEquals(3_000_000L, build.scanCells());
      assertEquals(3L, build.targetCapacity());
      assertEquals(0, calls.get());
      assertEquals(BlockGenerationResult.Status.LIMIT_EXCEEDED, build.generation().get().status());
      assertEquals(1, calls.get());
   }

   @Test
   void blockedBuildHasNoExecutableWork() {
      GeometryBuildResult build = GeometryBuildResult.blocked(Component.literal("blocked"));

      assertFalse(build.ready());
      assertEquals(0L, build.scanCells());
      assertEquals(0L, build.targetCapacity());
      BlockGenerationResult result = build.generation().get();
      assertEquals(BlockGenerationResult.Status.CONSTRAINTS_FAILED, result.status());
      assertTrue(result.blocks().isEmpty());
   }
}
