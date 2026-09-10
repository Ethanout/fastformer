package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ServerInputDispatcherTest {
   @Test
   void raycastRangeUsesActualDistanceAndRejectsInvalidRanges() {
      Vec3 origin = Vec3.ZERO;
      assertTrue(ServerInputDispatcher.isWithinRaycastRange(origin, new Vec3(3.0, 0.0, 0.0), 3.0));
      assertFalse(ServerInputDispatcher.isWithinRaycastRange(origin, new Vec3(3.01, 0.0, 0.0), 3.0));
      assertFalse(ServerInputDispatcher.isWithinRaycastRange(origin, new Vec3(1.0, 0.0, 0.0), -1.0));
      assertFalse(ServerInputDispatcher.isWithinRaycastRange(origin, new Vec3(1.0, 0.0, 0.0), Double.NaN));
   }
}
