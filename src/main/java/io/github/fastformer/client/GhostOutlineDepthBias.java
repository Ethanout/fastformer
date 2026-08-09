package io.github.fastformer.client;

import net.minecraft.world.phys.Vec3;

final class GhostOutlineDepthBias {
   private GhostOutlineDepthBias() {
   }

   static Vec3 towardCamera(Vec3 point, Vec3 camera, double distanceBias) {
      Vec3 towardCamera = camera.subtract(point);
      double distance = towardCamera.length();
      if (distance <= distanceBias) {
         return point;
      }
      return point.add(towardCamera.scale(distanceBias / distance));
   }
}
