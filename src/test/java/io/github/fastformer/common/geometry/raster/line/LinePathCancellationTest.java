package io.github.fastformer.common.geometry.raster.line;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LinePathCancellationTest {
   @Test
   void longPathCancelsInsideItsTraversal() {
      long[] scanned = {0};
      var observer = new BlockGenerationObserver() {
         @Override public void onScanned(long amount) { scanned[0] += amount; }
         @Override public void checkCancelled() {
            if (scanned[0] >= 12) throw new CancellationException("cancelled in line kernel");
         }
      };
      assertThrows(CancellationException.class, () -> LinePath.path(BlockPos.ZERO,
         new BlockPos(100_000, 50_000, 25_000), LineTieBias.DEFAULT, 100_001, observer));
      assertEquals(12, scanned[0]);
   }

   @Test
   void boundedKernelKeepsPathOrderAndCapsExtremeEndpoints() {
      for (LineTieBias tie : LineTieBias.values()) {
         BlockPos first = new BlockPos(-8, 4, -3);
         BlockPos second = new BlockPos(13, -7, 6);
         assertEquals(LinePath.path(first, second, tie), LinePath.path(first, second, tie, 100, null));
         assertEquals(8, LinePath.path(new BlockPos(Integer.MIN_VALUE, 0, 0),
            new BlockPos(Integer.MAX_VALUE, 0, 0), tie, 8, null).size());
      }
   }
}
