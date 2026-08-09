package io.github.fastformer.fastplace.geometry;

import java.util.Collection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record GeometryInteractionHit(GeometryInteractionTarget target, double rayDistance, Vec3 point) {
   private static final double EPSILON = 1.0E-7;

   public static GeometryInteractionHit from(Vec3 eye, Vec3 view, double reach, GeometryInteractionTarget target) {
      if (target == null) {
         return null;
      }
      Vec3 direction = normalize(view);
      if (eye == null || direction.lengthSqr() < EPSILON || !Double.isFinite(reach) || reach <= 0.0) {
         return null;
      }

      AABB bounds = target.bounds(0.0);
      Double distance = intersect(eye, direction, reach, bounds);
      if (distance == null) {
         return null;
      }
      return new GeometryInteractionHit(target, distance, eye.add(direction.scale(distance)));
   }

   public static GeometryInteractionHit nearest(Vec3 eye, Vec3 view, double reach, Collection<GeometryInteractionTarget> targets) {
      if (targets == null || targets.isEmpty()) {
         return null;
      }
      GeometryInteractionHit best = null;
      for (GeometryInteractionTarget target : targets) {
         GeometryInteractionHit hit = from(eye, view, reach, target);
         if (hit != null && (best == null || hit.rayDistance < best.rayDistance)) {
            best = hit;
         }
      }
      return best;
   }

   private static Double intersect(Vec3 eye, Vec3 direction, double reach, AABB bounds) {
      double minDistance = 0.0;
      double maxDistance = reach;

      double[] origins = {eye.x, eye.y, eye.z};
      double[] directions = {direction.x, direction.y, direction.z};
      double[] mins = {bounds.minX, bounds.minY, bounds.minZ};
      double[] maxs = {bounds.maxX, bounds.maxY, bounds.maxZ};

      for (int axis = 0; axis < 3; axis++) {
         double origin = origins[axis];
         double delta = directions[axis];
         double min = mins[axis];
         double max = maxs[axis];
         if (Math.abs(delta) < EPSILON) {
            if (origin < min || origin > max) {
               return null;
            }
            continue;
         }
         double inverse = 1.0 / delta;
         double near = (min - origin) * inverse;
         double far = (max - origin) * inverse;
         if (near > far) {
            double swap = near;
            near = far;
            far = swap;
         }
         minDistance = Math.max(minDistance, near);
         maxDistance = Math.min(maxDistance, far);
         if (minDistance > maxDistance) {
            return null;
         }
      }

      if (minDistance < 0.0 || minDistance > reach) {
         return null;
      }
      return minDistance;
   }

   private static Vec3 normalize(Vec3 vector) {
      if (vector == null || vector.lengthSqr() < EPSILON) {
         return Vec3.ZERO;
      }
      return vector.normalize();
   }
}
