package io.github.fastformer.common.geometry.model;

import static io.github.fastformer.common.math.MinecraftVectors.normalizeDirection;

import net.minecraft.world.phys.Vec3;

public record PlaneAxes(Vec3 horizontal, Vec3 vertical, Vec3 normal) {
   private static final double EPSILON = 1.0E-7;

   public static PlaneAxes fromNormal(Vec3 normal) {
      Vec3 localNormal = normalizeDirection(normal);
      Vec3 horizontal = normalizeDirection(new Vec3(0.0, 1.0, 0.0).cross(localNormal));
      if (horizontal.lengthSqr() < EPSILON) {
         horizontal = new Vec3(1.0, 0.0, 0.0);
      }
      Vec3 vertical = normalizeDirection(localNormal.cross(horizontal));
      return new PlaneAxes(horizontal, vertical, localNormal);
   }

}
