package io.github.fastformer.client.operation.selection;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class ScanCursorTest {
   @Test
   void countsAirVolumeBeforeAnyWorldRead() {
      assertThrows(IllegalArgumentException.class, () -> new ScanCursor(new AABB(0, 0, 0, 200, 200, 200), 2_000_000));
      assertThrows(IllegalArgumentException.class, () -> new ScanCursor(new AABB(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1), 2_000_000));
      assertThrows(IllegalArgumentException.class, () -> new ScanCursor(new AABB(30_000_000, 0, 0, 30_000_001, 1, 1), 2_000_000));
   }
   @Test
   void negativeFractionalBoundsVisitEveryIntersectingCellExactlyOnce() {
      var cursor = new ScanCursor(new AABB(-1.2, -0.5, -2, 1.1, 1, 0), 100);
      var positions = new HashSet<BlockPos>();
      while (cursor.hasNext()) assertTrue(positions.add(cursor.next()));
      assertEquals(16, positions.size());
      assertTrue(positions.contains(new BlockPos(-2, -1, -2)));
      assertTrue(positions.contains(new BlockPos(1, 0, -1)));
      assertThrows(java.util.NoSuchElementException.class, cursor::next);
   }
}
