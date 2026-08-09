package io.github.fastformer.fastplace.geometry;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class GeometryRayVisibility {
   private static final double SURFACE_EPSILON = 1.0E-4;

   private GeometryRayVisibility() {
   }

   public static double visibleReach(double maxReach, Vec3 eye, BlockHitResult firstBlockHit) {
      double limit = GeometryNumbers.finiteOr(maxReach, 0.0);
      if (limit <= 0.0 || eye == null || firstBlockHit == null || firstBlockHit.getType() != HitResult.Type.BLOCK) {
         return Math.max(0.0, limit);
      }
      double distance = eye.distanceTo(firstBlockHit.getLocation());
      if (!Double.isFinite(distance)) {
         return limit;
      }
      return Math.min(limit, Math.max(0.0, distance) + SURFACE_EPSILON);
   }
}
