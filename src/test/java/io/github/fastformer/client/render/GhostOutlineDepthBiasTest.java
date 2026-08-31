package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GhostOutlineDepthBiasTest {
   @Test
   void movesEverySharedVertexTowardTheCameraByOneStableDistance() {
      Vec3 camera = new Vec3(12.0, 9.0, -4.0);
      Vec3 shared = new Vec3(2.0, 3.0, 5.0);
      Vec3 firstUse = GhostOutlineDepthBias.towardCamera(shared, camera, 0.004);
      Vec3 secondUse = GhostOutlineDepthBias.towardCamera(shared, camera, 0.004);

      assertEquals(firstUse, secondUse);
      assertEquals(0.004, firstUse.distanceTo(shared), 1.0E-12);
      assertEquals(
         1.0,
         camera.subtract(shared).normalize().dot(firstUse.subtract(shared).normalize()),
         1.0E-12
      );
   }

   @Test
   void neverPushesAPointPastANearbyCamera() {
      Vec3 camera = new Vec3(0.001, 0.0, 0.0);
      Vec3 point = Vec3.ZERO;
      assertEquals(point, GhostOutlineDepthBias.towardCamera(point, camera, 0.004));
   }
}
