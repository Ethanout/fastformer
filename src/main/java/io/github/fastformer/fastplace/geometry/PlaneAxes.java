package io.github.fastformer.fastplace.geometry;

import net.minecraft.world.phys.Vec3;

public record PlaneAxes(Vec3 horizontal, Vec3 vertical, Vec3 normal) {
   private static final double EPSILON = 1.0E-7;

   public static PlaneAxes fromNormal(Vec3 normal) {
      Vec3 localNormal = normalize(normal);
      Vec3 horizontal = normalize(new Vec3(0.0, 1.0, 0.0).cross(localNormal));
      if (horizontal.lengthSqr() < EPSILON) {
         horizontal = new Vec3(1.0, 0.0, 0.0);
      }
      Vec3 vertical = normalize(localNormal.cross(horizontal));
      return new PlaneAxes(horizontal, vertical, localNormal);
   }

   private static Vec3 normalize(Vec3 vector) {
      return vector.lengthSqr() < EPSILON ? Vec3.ZERO : vector.normalize();
   }
}
