package io.github.fastformer.network;

import net.minecraft.world.phys.Vec3;

/** Checks numeric and spatial bounds before a captured ray reaches the world. */
public final class RaySnapshotValidation {
   private RaySnapshotValidation() { }

   public static boolean valid(Vec3 eye, Vec3 view) {
      return finite(eye) && finite(view) && Double.isFinite(view.lengthSqr()) && view.lengthSqr() > 1.0E-12;
   }

   public static boolean near(Vec3 eye, Vec3 currentEye, double range) {
      return finite(eye) && finite(currentEye) && Double.isFinite(range) && range >= 0
         && eye.distanceToSqr(currentEye) <= range * range;
   }

   private static boolean finite(Vec3 value) {
      return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
   }
}
