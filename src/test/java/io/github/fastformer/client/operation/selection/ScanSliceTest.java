package io.github.fastformer.client.operation.selection;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.phys.AABB;
import static org.junit.jupiter.api.Assertions.*;

class ScanSliceTest {
   @Test
   void recordsBoundedSparseScanMetrics() {
      var cursor = new ScanCursor(new AABB(0, 0, 0, 200, 100, 100), 2_000_000);
      long peak = 0, heap = 0, scanned = 0;
      int ticks = 0;
      while (cursor.hasNext()) {
         long start = System.nanoTime();
         int cells = ScanSlice.run(cursor, 4096, start + 2_000_000, System::nanoTime, pos -> {});
         peak = Math.max(peak, System.nanoTime() - start);
         assertTrue(cells <= 4096);
         scanned += cells;
         ticks++;
         heap = Math.max(heap, java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
      }
      assertEquals(2_000_000, scanned);
      System.out.printf("SCAN_BUDGET cells=%d slices=%d peakSliceNanos=%d peakJvmHeapBytes=%d worldReader=synthetic-air%n", scanned, ticks, peak, heap);
   }
   @Test
   void sparseScanYieldsAtCellLimitAndAtTimeLimit() {
      var cursor = new ScanCursor(new AABB(0, 0, 0, 100, 100, 100), 2_000_000);
      assertEquals(4096, ScanSlice.run(cursor, 4096, 2_000_000, () -> 0, pos -> {}));
      var clock = new AtomicLong();
      assertEquals(4, ScanSlice.run(cursor, 4096, 2_000_000, clock::get, pos -> clock.addAndGet(500_000)));
      assertTrue(cursor.hasNext());
      assertEquals(0, ScanSlice.run(cursor, 4096, 2_000_000, clock::get, pos -> fail("expired shared budget read the world")));
   }
}
