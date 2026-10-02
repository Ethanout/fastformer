package io.github.fastformer.fastplace.geometry.raycast;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NearBlockRaycastTest {
   @Test
   void toleranceDecreasesToZero() {
      assertEquals(0.25, NearBlockRaycast.radiusAt(0));
      assertTrue(NearBlockRaycast.radiusAt(2) > NearBlockRaycast.radiusAt(12));
      assertEquals(0, NearBlockRaycast.radiusAt(20));
      assertEquals(0, NearBlockRaycast.radiusAt(40));
   }

   @Test
   void assistedHitStaysOnActualSurface() {
      var box = new AABB(0, 0, 3, 1, 0.5, 4);
      var start = new Vec3(1.1, 0.25, 0);
      var end = start.add(0, 0, 10);
      assertNull(NearBlockRaycast.clipBox(box, new BlockPos(0, 0, 3), start, end, 0));
      var hit = NearBlockRaycast.clipBox(box, new BlockPos(0, 0, 3), start, end, 0.2);
      assertNotNull(hit);
      assertEquals(new Vec3(1, 0.25, 3), hit.getLocation());
      assertEquals(Direction.NORTH, hit.getDirection());
   }

   @Test
   void exactSlabHitIsNotMovedByTolerance() {
      var box = new AABB(0, 0, 3, 1, 0.5, 4);
      var hit = NearBlockRaycast.clipBox(box, new BlockPos(0, 0, 3),
         new Vec3(0.5, 2, 3.5), new Vec3(0.5, -2, 3.5), 0.25);
      assertNotNull(hit);
      assertEquals(new Vec3(0.5, 0.5, 3.5), hit.getLocation());
      assertEquals(Direction.UP, hit.getDirection());
   }
}
